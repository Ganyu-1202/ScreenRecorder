package org.firefly.screenrec;
import org.junit.jupiter.api.Test;
import org.firefly.screenrec.core.audio.WasapiLoopback;
import static org.junit.jupiter.api.Assertions.*;
class WasapiLoopbackTest {
 @Test void parsesHeadphonesAndSpeakersAsSystemOutput() {
  var ds=WasapiLoopback.parseDevices("id1\t耳机 (USB Audio)\nid2\tSpeakers (Realtek)\n");
  assertEquals(2,ds.size());
  assertTrue(ds.stream().allMatch(d -> d.isSystemAudio()));
  assertEquals("wasapi-loopback",ds.get(0).ffmpegInputFormat());
  assertEquals("id1",ds.get(0).ffmpegInputName());
  assertEquals("耳机 (USB Audio)",ds.get(0).name());
 }
 @Test void ignoresMalformedListing() { assertTrue(WasapiLoopback.parseDevices("error\n").isEmpty()); }
}
