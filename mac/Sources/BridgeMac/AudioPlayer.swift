import AVFoundation
import AudioToolbox

/// Plays scrcpy's AAC audio stream: 48 kHz stereo, one 1024-frame AAC packet
/// per scrcpy packet, preceded by a "config" packet (the AudioSpecificConfig,
/// which AudioToolbox calls the magic cookie). AudioConverter decodes to
/// Float32 PCM and an AVAudioPlayerNode plays it as the buffers arrive.
final class AudioPlayer {
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let format = AVAudioFormat(standardFormatWithSampleRate: 48000, channels: 2)!
    private var converter: AudioConverterRef?
    private var started = false
    private var queued = 0        // buffers scheduled but not yet played
    private let lock = NSLock()

    init() {
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
    }

    func stop() {
        node.stop()
        engine.stop()
        if let c = converter { AudioConverterDispose(c); converter = nil }
    }

    /// Called on the audio network thread.
    func handle(packet: [UInt8], isConfig: Bool) {
        if isConfig { setUp(cookie: packet); return }
        guard let converter = converter, started else { return }
        guard let pcm = decode(packet, with: converter) else { return }

        // Don't let a slow link pile up seconds of audio: drop if we're far ahead.
        lock.lock(); let backlog = queued; lock.unlock()
        if backlog > 12 { return }   // ~250 ms
        lock.lock(); queued += 1; lock.unlock()
        node.scheduleBuffer(pcm) { [weak self] in
            guard let self = self else { return }
            self.lock.lock(); self.queued -= 1; self.lock.unlock()
        }
        if !node.isPlaying { node.play() }
    }

    private func setUp(cookie: [UInt8]) {
        if let c = converter { AudioConverterDispose(c); converter = nil }
        var input = AudioStreamBasicDescription(
            mSampleRate: 48000, mFormatID: kAudioFormatMPEG4AAC, mFormatFlags: 0,
            mBytesPerPacket: 0, mFramesPerPacket: 1024, mBytesPerFrame: 0,
            mChannelsPerFrame: 2, mBitsPerChannel: 0, mReserved: 0)
        var output = format.streamDescription.pointee
        var c: AudioConverterRef?
        guard AudioConverterNew(&input, &output, &c) == noErr, let conv = c else { return }
        // CoreAudio wants the AudioSpecificConfig wrapped in an MPEG-4 ES descriptor
        // (what an .mp4's "esds" box holds), not the bare 2 bytes scrcpy sends.
        let wrapped = esds(cookie)
        wrapped.withUnsafeBytes { _ = AudioConverterSetProperty(conv, kAudioConverterDecompressionMagicCookie, UInt32(wrapped.count), $0.baseAddress!) }
        converter = conv
        if !started {
            do { try engine.start(); started = true } catch { return }
        }
    }

    /// ES_Descriptor { DecoderConfigDescriptor { DecoderSpecificInfo = asc }, SLConfig }.
    private func esds(_ asc: [UInt8]) -> [UInt8] {
        // Sizes here are a handful of bytes; the 4-byte varint form covers 127 at most per byte.
        func tag(_ t: UInt8, _ body: [UInt8]) -> [UInt8] { [t, 0x80, 0x80, 0x80, UInt8(min(body.count, 127))] + body }
        let dsi = tag(0x05, asc)
        let dcd = tag(0x04, [0x40, 0x15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0] + dsi)   // AAC, audio stream
        return tag(0x03, [0, 0, 0] + dcd + tag(0x06, [0x02]))
    }

    /// One AAC packet in, up to 1024 frames of PCM out.
    private func decode(_ packet: [UInt8], with converter: AudioConverterRef) -> AVAudioPCMBuffer? {
        guard let pcm = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 1024) else { return nil }
        pcm.frameLength = 1024   // the converter reads the output sizes from this
        var frames: UInt32 = 1024
        // Copy the packet into memory we own for the duration of the call; the
        // converter's callback hands AudioToolbox a pointer into it.
        let raw = UnsafeMutableRawPointer.allocate(byteCount: packet.count, alignment: 16)
        defer { raw.deallocate() }
        guard !packet.isEmpty else { return nil }
        packet.withUnsafeBytes { if let base = $0.baseAddress { raw.copyMemory(from: base, byteCount: packet.count) } }
        var feed = Feed(data: raw, size: UInt32(packet.count))
        let status = withUnsafeMutablePointer(to: &feed) { feedPtr in
            AudioConverterFillComplexBuffer(converter, { _, packetCount, buffers, descriptions, userData in
                let feed = userData!.assumingMemoryBound(to: Feed.self)
                if feed.pointee.consumed { packetCount.pointee = 0; return 1 }   // no more data this call
                feed.pointee.consumed = true
                buffers.pointee.mNumberBuffers = 1
                buffers.pointee.mBuffers.mData = feed.pointee.data
                buffers.pointee.mBuffers.mDataByteSize = feed.pointee.size
                buffers.pointee.mBuffers.mNumberChannels = 2
                feed.pointee.description = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: feed.pointee.size)
                descriptions?.pointee = withUnsafeMutablePointer(to: &feed.pointee.description) { $0 }
                packetCount.pointee = 1
                return noErr
            }, feedPtr, &frames, pcm.mutableAudioBufferList, nil)
        }
        guard status == noErr || status == 1, frames > 0 else { return nil }
        pcm.frameLength = frames
        return pcm
    }

    private struct Feed {
        var data: UnsafeMutableRawPointer
        var size: UInt32
        var consumed = false
        var description = AudioStreamPacketDescription()
    }
}
