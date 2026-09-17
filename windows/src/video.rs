//! Turns scrcpy's raw H.264 packets into RGBA frames.
//!
//! Media Foundation's H.264 decoder (a synchronous MFT) accepts the Annex B
//! byte stream scrcpy sends (start codes, SPS/PPS inline in the "config"
//! packet), so no re-framing is needed, unlike the Mac's AVCC conversion.
//! It hands back NV12; the frame is converted to RGBA on the CPU for the
//! window. Low-latency mode is set so it never buffers frames for reordering.

use anyhow::{anyhow, bail, Result};
use std::mem::ManuallyDrop;
use windows::core::{Interface, GUID};
use windows::Win32::Media::MediaFoundation::*;

/// The Microsoft H.264 decoder MFT (CLSID_CMSH264DecoderMFT), not exported by
/// name in the bindings: {62CE7E72-4C71-4d20-B15D-452831A87D9D}.
const H264_DECODER: GUID = GUID::from_u128(0x62ce7e72_4c71_4d20_b15d_452831a87d9d);
use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED};

/// One decoded frame, RGBA, tightly packed.
pub struct Frame {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

pub struct Decoder {
    mft: IMFTransform,
    width: u32,
    height: u32,
    stride: u32,
    output_size: u32,
    waiting_for_keyframe: bool,
}

impl Decoder {
    pub fn new() -> Result<Decoder> {
        unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            MFStartup(MF_VERSION, MFSTARTUP_FULL)?;
            let mft: IMFTransform = CoCreateInstance(&H264_DECODER, None, CLSCTX_INPROC_SERVER)?;

            // Lowest latency: decode and hand over each frame as it comes.
            if let Ok(attrs) = mft.GetAttributes() {
                let _ = attrs.SetUINT32(&MF_LOW_LATENCY, 1);
            }

            let input = MFCreateMediaType()?;
            input.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            input.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264)?;
            mft.SetInputType(0, &input, 0)?;

            let mut d = Decoder { mft, width: 0, height: 0, stride: 0, output_size: 0, waiting_for_keyframe: true };
            d.choose_output_type()?;
            d.mft.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)?;
            d.mft.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)?;
            Ok(d)
        }
    }

    /// Picks NV12 among the decoder's offered output types and reads the frame size.
    fn choose_output_type(&mut self) -> Result<()> {
        unsafe {
            let mut i = 0;
            loop {
                let Ok(t) = self.mft.GetOutputAvailableType(0, i) else { bail!("decoder offers no NV12 output") };
                if t.GetGUID(&MF_MT_SUBTYPE)? == MFVideoFormat_NV12 {
                    self.mft.SetOutputType(0, &t, 0)?;
                    let size = t.GetUINT64(&MF_MT_FRAME_SIZE).unwrap_or(0);
                    self.width = (size >> 32) as u32;
                    self.height = (size & 0xffff_ffff) as u32;
                    self.stride = t.GetUINT32(&MF_MT_DEFAULT_STRIDE).unwrap_or(self.width);
                    let info = self.mft.GetOutputStreamInfo(0)?;
                    self.output_size = info.cbSize.max(self.stride * self.height * 3 / 2);
                    return Ok(());
                }
                i += 1;
            }
        }
    }

    /// Feeds one packet; returns any frames that came out.
    pub fn handle(&mut self, packet: &[u8], is_config: bool, is_keyframe: bool, pts_us: i64) -> Result<Vec<Frame>> {
        if is_config {
            self.waiting_for_keyframe = true;
        } else if self.waiting_for_keyframe {
            if !is_keyframe {
                return Ok(Vec::new());
            }
            self.waiting_for_keyframe = false;
        }
        unsafe {
            let buffer = MFCreateMemoryBuffer(packet.len() as u32)?;
            let mut ptr = std::ptr::null_mut();
            buffer.Lock(&mut ptr, None, None)?;
            std::ptr::copy_nonoverlapping(packet.as_ptr(), ptr, packet.len());
            buffer.Unlock()?;
            buffer.SetCurrentLength(packet.len() as u32)?;
            let sample = MFCreateSample()?;
            sample.AddBuffer(&buffer)?;
            sample.SetSampleTime(pts_us * 10)?; // 100 ns units
            match self.mft.ProcessInput(0, &sample, 0) {
                Ok(()) => {}
                Err(e) if e.code() == MF_E_NOTACCEPTING => {} // drain first, below
                Err(e) => return Err(anyhow!("decoder input: {e}")),
            }
        }
        self.drain()
    }

    fn drain(&mut self) -> Result<Vec<Frame>> {
        let mut frames = Vec::new();
        loop {
            unsafe {
                let sample = MFCreateSample()?;
                let buffer = MFCreateMemoryBuffer(self.output_size.max(1))?;
                sample.AddBuffer(&buffer)?;
                let mut out = [MFT_OUTPUT_DATA_BUFFER {
                    dwStreamID: 0,
                    pSample: ManuallyDrop::new(Some(sample.clone())),
                    dwStatus: 0,
                    pEvents: ManuallyDrop::new(None),
                }];
                let mut status = 0u32;
                let result = self.mft.ProcessOutput(0, &mut out, &mut status);
                // Whatever ProcessOutput put in the slot is ours to release.
                let _ = ManuallyDrop::into_inner(std::ptr::read(&out[0].pSample));
                let _ = ManuallyDrop::into_inner(std::ptr::read(&out[0].pEvents));
                match result {
                    Ok(()) => {
                        if let Some(f) = self.frame_from(&sample)? {
                            frames.push(f);
                        }
                    }
                    Err(e) if e.code() == MF_E_TRANSFORM_NEED_MORE_INPUT => return Ok(frames),
                    Err(e) if e.code() == MF_E_TRANSFORM_STREAM_CHANGE => {
                        // Resolution known now (or changed): pick the output type again.
                        self.choose_output_type()?;
                        continue;
                    }
                    Err(e) => return Err(anyhow!("decoder output: {e}")),
                }
            }
        }
    }

    /// NV12 → RGBA. Plain integer BT.601 conversion; fast enough for 1080p.
    fn frame_from(&self, sample: &IMFSample) -> Result<Option<Frame>> {
        if self.width == 0 || self.height == 0 {
            return Ok(None);
        }
        unsafe {
            let buffer = sample.ConvertToContiguousBuffer()?;
            let mut ptr = std::ptr::null_mut();
            let mut len = 0u32;
            buffer.Lock(&mut ptr, None, Some(&mut len))?;
            let data = std::slice::from_raw_parts(ptr, len as usize);
            let (w, h, stride) = (self.width as usize, self.height as usize, self.stride as usize);
            let frame = if data.len() >= stride * h * 3 / 2 {
                let mut rgba = vec![0u8; w * h * 4];
                let uv_base = stride * h;
                for y in 0..h {
                    let yrow = &data[y * stride..y * stride + w];
                    let uvrow = &data[uv_base + (y / 2) * stride..uv_base + (y / 2) * stride + w];
                    let out = &mut rgba[y * w * 4..(y + 1) * w * 4];
                    for x in 0..w {
                        let yy = (yrow[x] as i32 - 16) * 298;
                        let u = uvrow[x & !1] as i32 - 128;
                        let v = uvrow[(x & !1) + 1] as i32 - 128;
                        let r = (yy + 409 * v + 128) >> 8;
                        let g = (yy - 100 * u - 208 * v + 128) >> 8;
                        let b = (yy + 516 * u + 128) >> 8;
                        let o = x * 4;
                        out[o] = r.clamp(0, 255) as u8;
                        out[o + 1] = g.clamp(0, 255) as u8;
                        out[o + 2] = b.clamp(0, 255) as u8;
                        out[o + 3] = 255;
                    }
                }
                Some(Frame { width: self.width, height: self.height, rgba })
            } else {
                None
            };
            buffer.Unlock()?;
            Ok(frame)
        }
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        unsafe {
            let _ = self.mft.ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
            let _ = self.mft.ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
        }
    }
}

// Keep the Interface import used (cast is handy when debugging types).
#[allow(dead_code)]
fn _uses_interface(t: &IMFTransform) -> bool {
    t.cast::<IMFTransform>().is_ok()
}
