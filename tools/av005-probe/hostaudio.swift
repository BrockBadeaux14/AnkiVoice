// AV-005 host audio routing helper.
//
// The emulator forwards the host's *default* input device when `adb emu avd hostmicon on`
// is set, and plays guest audio to the host's default output device. The probe therefore
// needs both defaults pinned to known hardware and restored afterwards. Usage:
//
//   hostaudio list
//   hostaudio get
//   hostaudio set-input  "MacBook Pro Microphone"
//   hostaudio set-output "MacBook Pro Speakers"

import CoreAudio
import Foundation

func deviceIDs() -> [AudioDeviceID] {
    var address = AudioObjectPropertyAddress(
        mSelector: kAudioHardwarePropertyDevices,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain)
    var size: UInt32 = 0
    guard AudioObjectGetPropertyDataSize(
        AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size) == noErr else { return [] }
    var ids = [AudioDeviceID](repeating: 0, count: Int(size) / MemoryLayout<AudioDeviceID>.size)
    guard AudioObjectGetPropertyData(
        AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &ids) == noErr else { return [] }
    return ids
}

func name(of device: AudioDeviceID) -> String {
    var address = AudioObjectPropertyAddress(
        mSelector: kAudioObjectPropertyName,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain)
    var value: CFString = "" as CFString
    var size = UInt32(MemoryLayout<CFString>.size)
    guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, &value) == noErr else { return "" }
    return value as String
}

func channelCount(of device: AudioDeviceID, scope: AudioObjectPropertyScope) -> Int {
    var address = AudioObjectPropertyAddress(
        mSelector: kAudioDevicePropertyStreamConfiguration,
        mScope: scope,
        mElement: kAudioObjectPropertyElementMain)
    var size: UInt32 = 0
    guard AudioObjectGetPropertyDataSize(device, &address, 0, nil, &size) == noErr, size > 0 else { return 0 }
    let buffer = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: 16)
    defer { buffer.deallocate() }
    guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, buffer) == noErr else { return 0 }
    let lists = UnsafeMutableAudioBufferListPointer(buffer.assumingMemoryBound(to: AudioBufferList.self))
    return lists.reduce(0) { $0 + Int($1.mNumberChannels) }
}

func defaultDevice(_ selector: AudioObjectPropertySelector) -> AudioDeviceID {
    var address = AudioObjectPropertyAddress(
        mSelector: selector,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain)
    var device = AudioDeviceID(0)
    var size = UInt32(MemoryLayout<AudioDeviceID>.size)
    _ = AudioObjectGetPropertyData(
        AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device)
    return device
}

func setDefault(_ selector: AudioObjectPropertySelector, _ device: AudioDeviceID) -> Bool {
    var address = AudioObjectPropertyAddress(
        mSelector: selector,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain)
    var value = device
    let size = UInt32(MemoryLayout<AudioDeviceID>.size)
    return AudioObjectSetPropertyData(
        AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, size, &value) == noErr
}

func find(_ wanted: String, scope: AudioObjectPropertyScope) -> AudioDeviceID? {
    deviceIDs().first { channelCount(of: $0, scope: scope) > 0 && name(of: $0) == wanted }
}

let arguments = CommandLine.arguments
let command = arguments.count > 1 ? arguments[1] : "get"

switch command {
case "list":
    for device in deviceIDs() {
        let input = channelCount(of: device, scope: kAudioObjectPropertyScopeInput)
        let output = channelCount(of: device, scope: kAudioObjectPropertyScopeOutput)
        print("\(device)\tin=\(input)\tout=\(output)\t\(name(of: device))")
    }
case "get":
    let input = defaultDevice(kAudioHardwarePropertyDefaultInputDevice)
    let output = defaultDevice(kAudioHardwarePropertyDefaultOutputDevice)
    let record: [String: String] = [
        "default_input": name(of: input),
        "default_output": name(of: output),
    ]
    let data = try JSONSerialization.data(withJSONObject: record, options: [.sortedKeys, .prettyPrinted])
    print(String(data: data, encoding: .utf8)!)
case "set-input", "set-output":
    guard arguments.count > 2 else { FileHandle.standardError.write(Data("device name required\n".utf8)); exit(2) }
    let isInput = command == "set-input"
    let scope = isInput ? kAudioObjectPropertyScopeInput : kAudioObjectPropertyScopeOutput
    guard let device = find(arguments[2], scope: scope) else {
        FileHandle.standardError.write(Data("no such device: \(arguments[2])\n".utf8)); exit(3)
    }
    let selector = isInput
        ? kAudioHardwarePropertyDefaultInputDevice
        : kAudioHardwarePropertyDefaultOutputDevice
    guard setDefault(selector, device) else {
        FileHandle.standardError.write(Data("could not set default\n".utf8)); exit(4)
    }
    print("\(command) \(name(of: device))")
default:
    FileHandle.standardError.write(Data("usage: hostaudio list|get|set-input NAME|set-output NAME\n".utf8))
    exit(2)
}
