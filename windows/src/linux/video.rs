//! Turns scrcpy's raw H.264 packets into RGBA frames with OpenH264, Cisco's
//! software decoder, built into the binary so there's nothing to install.
//! Linux has no decoder every machine is sure to have; hardware decoding
//! (VA-API) would be a later step for lower CPU use.

use anyhow::{anyhow, Result};
use openh264::decoder::Decoder as H264;
use openh264::formats::YUVSource;

/// One decoded frame, RGBA, tightly packed.
pub struct Frame {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

pub struct Decoder {
    inner: H264,
    waiting_for_keyframe: bool,
}

impl Decoder {
    pub fn new() -> Result<Decoder> {
        let inner = H264::new().map_err(|e| anyhow!("starting the H.264 decoder: {e}"))?;
        Ok(Decoder { inner, waiting_for_keyframe: true })
    }

    /// Feeds one packet; returns any frames that came out.
    pub fn handle(&mut self, packet: &[u8], is_config: bool, is_keyframe: bool, _pts_us: i64) -> Result<Vec<Frame>> {
        if is_config {
            self.waiting_for_keyframe = true;
        } else if self.waiting_for_keyframe {
            if !is_keyframe {
                return Ok(Vec::new());
            }
            self.waiting_for_keyframe = false;
        }
        match self.inner.decode(packet) {
            Ok(Some(yuv)) => {
                let (w, h) = yuv.dimensions();
                let mut rgba = vec![0u8; w * h * 4];
                yuv.write_rgba8(&mut rgba);
                Ok(vec![Frame { width: w as u32, height: h as u32, rgba }])
            }
            Ok(None) => Ok(Vec::new()),
            // A damaged packet shouldn't end the session: skip to the next keyframe.
            Err(e) => {
                crate::log!("video", "decoder: {e}");
                self.waiting_for_keyframe = true;
                Ok(Vec::new())
            }
        }
    }
}
