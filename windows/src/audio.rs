//! The phone's audio: scrcpy sends raw AAC-LC frames (48 kHz stereo) after a
//! 2-byte AudioSpecificConfig "config" packet. Decoded in pure Rust
//! (symphonia) and played through the default output device (cpal → WASAPI).
//! Best-effort: any failure just means no sound; the video decides when the
//! session ends.

use anyhow::{anyhow, Result};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use symphonia::core::audio::{AudioBufferRef, Signal};
use symphonia::core::codecs::{CodecParameters, Decoder as _, CODEC_TYPE_AAC};
use symphonia::core::formats::Packet;

pub struct AudioPlayer {
    decoder: Option<Box<dyn symphonia::core::codecs::Decoder>>,
    queue: Arc<Mutex<VecDeque<f32>>>,
    _stream: Option<cpal::Stream>,
    sample_rate: u32,
    channels: u16,
}

impl AudioPlayer {
    pub fn new() -> AudioPlayer {
        AudioPlayer { decoder: None, queue: Arc::new(Mutex::new(VecDeque::new())), _stream: None, sample_rate: 48000, channels: 2 }
    }

    /// `is_config` packets carry the AudioSpecificConfig; everything after is a frame.
    pub fn handle(&mut self, packet: &[u8], is_config: bool) {
        if is_config {
            if let Err(e) = self.configure(packet) {
                crate::log!("audio", "no audio: {e:#}");
            }
            return;
        }
        let Some(dec) = self.decoder.as_mut() else { return };
        let p = Packet::new_from_slice(0, 0, 0, packet);
        match dec.decode(&p) {
            Ok(decoded) => {
                let mut q = self.queue.lock().unwrap();
                // Never let latency build up: if the sink fell behind, drop the backlog.
                if q.len() > (self.sample_rate as usize) * (self.channels as usize) / 2 {
                    q.clear();
                }
                push_interleaved(&decoded, &mut q);
            }
            Err(e) => crate::log!("audio", "decode: {e}"),
        }
    }

    fn configure(&mut self, asc: &[u8]) -> Result<()> {
        // AudioSpecificConfig: 5 bits object type, 4 bits sample-rate index, 4 bits channels.
        if asc.len() < 2 {
            return Err(anyhow!("short AudioSpecificConfig"));
        }
        let rate_index = ((asc[0] & 0x07) << 1) | (asc[1] >> 7);
        let rates = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350];
        self.sample_rate = rates.get(rate_index as usize).copied().unwrap_or(48000);
        self.channels = ((asc[1] >> 3) & 0x0f).max(1) as u16;

        let mut params = CodecParameters::new();
        params.for_codec(CODEC_TYPE_AAC).with_sample_rate(self.sample_rate).with_extra_data(asc.to_vec().into_boxed_slice());
        let dec = symphonia::default::get_codecs().make(&params, &Default::default())?;
        self.decoder = Some(dec);

        // Output stream: pull interleaved f32 from the queue, silence when empty.
        let host = cpal::default_host();
        let device = host.default_output_device().ok_or_else(|| anyhow!("no output device"))?;
        let config = cpal::StreamConfig {
            channels: self.channels,
            sample_rate: cpal::SampleRate(self.sample_rate),
            buffer_size: cpal::BufferSize::Default,
        };
        let queue = self.queue.clone();
        let stream = device.build_output_stream(
            &config,
            move |out: &mut [f32], _| {
                let mut q = queue.lock().unwrap();
                for s in out.iter_mut() {
                    *s = q.pop_front().unwrap_or(0.0);
                }
            },
            |e| crate::log!("audio", "output: {e}"),
            None,
        )?;
        stream.play()?;
        self._stream = Some(stream);
        crate::log!("audio", "{} Hz, {} channels", self.sample_rate, self.channels);
        Ok(())
    }
}

fn push_interleaved(buf: &AudioBufferRef, q: &mut VecDeque<f32>) {
    match buf {
        AudioBufferRef::F32(b) => {
            let frames = b.frames();
            let ch = b.spec().channels.count();
            for i in 0..frames {
                for c in 0..ch {
                    q.push_back(b.chan(c)[i]);
                }
            }
        }
        other => {
            // Convert anything else through a float copy.
            let mut f = symphonia::core::audio::AudioBuffer::<f32>::new(other.capacity() as u64, *other.spec());
            other.convert(&mut f);
            let frames = f.frames();
            let ch = f.spec().channels.count();
            for i in 0..frames {
                for c in 0..ch {
                    q.push_back(f.chan(c)[i]);
                }
            }
        }
    }
}
