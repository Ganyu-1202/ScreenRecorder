package org.firefly.screenrec;
import org.junit.jupiter.api.Test;
import org.firefly.screenrec.core.audio.AudioDeviceService;
import static org.junit.jupiter.api.Assertions.*;
class AudioDeviceDetectionTest {
 @Test void fourRealInputsRemainSelectable() {
  var devices=AudioDeviceService.parseWindowsDevices("""
   [dshow @ 1] "Microphone (USB Audio)" (audio)
   [dshow @ 1] Alternative name "@device_cm_1"
   [dshow @ 1] "Microphone Array (Realtek)" (audio)
   [dshow @ 1] "Line In" (audio)
   [dshow @ 1] "Custom virtual input" (audio)
   [dshow @ 1] "Camera" (video)
   """);
  assertEquals(4,devices.size());
  assertTrue(devices.stream().allMatch(d -> d.isMicrophone()));
 }
 @Test void recognizesLoopbackAndPreservesUnicodeNames() {
  var devices=AudioDeviceService.parseWindowsDevices("[dshow @ 1] \"立体声混音 (Realtek)\" (audio)");
  assertTrue(devices.get(0).isSystemAudio());
  assertEquals("audio=立体声混音 (Realtek)",devices.get(0).ffmpegInputName());
 }
 @Test void failedEnumerationDoesNotInventDefaultDevice() {
  assertTrue(AudioDeviceService.parseWindowsDevices("Could not enumerate audio devices").isEmpty());
 }
}
