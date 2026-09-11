import AppKit
import AVFoundation
import CoreMedia

/// Turns scrcpy's raw H.264 packets into pixels.
///
/// The stream is "Annex B": NAL units separated by 00 00 00 01 start codes.
/// Apple's decoder wants "AVCC": each NAL prefixed by its 4-byte length, plus a
/// format description built from the SPS/PPS (which scrcpy sends first as a
/// "config" packet). AVSampleBufferDisplayLayer then decodes and displays the
/// frames itself, hardware accelerated, no VideoToolbox session to manage.
final class H264Player: NSView {
    let layerView = AVSampleBufferDisplayLayer()
    private var format: CMVideoFormatDescription?
    private var waitingForKeyframe = true

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layerView.videoGravity = .resizeAspect
        layerView.backgroundColor = NSColor.black.cgColor
        layer = layerView
    }

    required init?(coder: NSCoder) { fatalError() }

    /// Called on any thread; enqueueing is dispatched to main.
    func handle(packet: [UInt8], isConfig: Bool, isKeyframe: Bool) {
        if isConfig {
            format = makeFormat(fromConfig: packet)
            waitingForKeyframe = true
            DispatchQueue.main.async { self.layerView.flush() }
            return
        }
        guard let format = format else { return }
        if waitingForKeyframe {
            guard isKeyframe else { return }
            waitingForKeyframe = false
        }
        guard let sample = makeSample(annexB: packet, format: format) else { return }
        DispatchQueue.main.async {
            if self.layerView.status == .failed {
                self.layerView.flush()
                self.waitingForKeyframe = true
            }
            self.layerView.enqueue(sample)
        }
    }

    // MARK: - Annex B parsing

    /// Splits on start codes (00 00 01 or 00 00 00 01) and returns the NAL payloads.
    private func nalUnits(_ data: [UInt8]) -> [ArraySlice<UInt8>] {
        var starts = [Int]()
        var i = 0
        while i + 2 < data.count {
            if data[i] == 0 && data[i + 1] == 0 {
                if data[i + 2] == 1 { starts.append(i + 3); i += 3; continue }
                if i + 3 < data.count && data[i + 2] == 0 && data[i + 3] == 1 { starts.append(i + 4); i += 4; continue }
            }
            i += 1
        }
        var units = [ArraySlice<UInt8>]()
        for (n, start) in starts.enumerated() {
            var end = n + 1 < starts.count ? starts[n + 1] : data.count
            if n + 1 < starts.count {
                // Trim the next start code (3 or 4 bytes) from this unit's end.
                end -= (data[end - 4] == 0 && data[end - 3] == 0 && data[end - 2] == 0) ? 4 : 3
            }
            units.append(data[start..<end])
        }
        return units
    }

    private func makeFormat(fromConfig data: [UInt8]) -> CMVideoFormatDescription? {
        let units = nalUnits(data)
        guard let sps = units.first(where: { ($0.first ?? 0) & 0x1f == 7 }),
              let pps = units.first(where: { ($0.first ?? 0) & 0x1f == 8 }) else { return nil }
        let spsArray = Array(sps), ppsArray = Array(pps)
        var format: CMVideoFormatDescription?
        let status = spsArray.withUnsafeBufferPointer { spsPtr in
            ppsArray.withUnsafeBufferPointer { ppsPtr in
                let pointers = [spsPtr.baseAddress!, ppsPtr.baseAddress!]
                let sizes = [spsArray.count, ppsArray.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2,
                    parameterSetPointers: pointers, parameterSetSizes: sizes,
                    nalUnitHeaderLength: 4, formatDescriptionOut: &format)
            }
        }
        return status == noErr ? format : nil
    }

    private func makeSample(annexB: [UInt8], format: CMVideoFormatDescription) -> CMSampleBuffer? {
        // Rewrite as AVCC: 4-byte big-endian length before each NAL.
        var avcc = [UInt8]()
        avcc.reserveCapacity(annexB.count + 8)
        for unit in nalUnits(annexB) {
            let len = UInt32(unit.count).bigEndian
            avcc += withUnsafeBytes(of: len) { Array($0) }
            avcc += unit
        }
        guard !avcc.isEmpty else { return nil }

        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: avcc.count,
            blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
            dataLength: avcc.count, flags: 0, blockBufferOut: &block) == noErr, let blockBuffer = block else { return nil }
        avcc.withUnsafeBytes { _ = CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: blockBuffer, offsetIntoDestination: 0, dataLength: avcc.count) }

        var sample: CMSampleBuffer?
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: .invalid, decodeTimeStamp: .invalid)
        var size = avcc.count
        guard CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: blockBuffer, formatDescription: format,
            sampleCount: 1, sampleTimingEntryCount: 1, sampleTimingArray: &timing,
            sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &sample) == noErr,
            let sampleBuffer = sample else { return nil }

        // No timestamps: show each frame as soon as it's decoded (lowest latency).
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: true) as? [CFMutableDictionary],
           let first = attachments.first {
            CFDictionarySetValue(first, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sampleBuffer
    }
}
