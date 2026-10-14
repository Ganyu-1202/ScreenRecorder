package org.firefly.screenrec;
import org.firefly.screenrec.core.recorder.*;
import org.firefly.screenrec.core.settings.AppSettings;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class RecordingSourceTest {
 @Test void windowForcesGdiAndExplicitBitrate() {
  var p=RecordingProfile.builder().source(CaptureSource.window("My app",0,0,800,600))
    .outputFile(Path.of("out.mp4")).encoder(EncoderBackend.LIBX264)
    .captureBackend(CaptureBackend.DDAGrab).explicitBitrateBps(8_000_000).build();
  var cmd=new FfmpegCommandBuilder(Path.of("ffmpeg.exe")).build(p);
  assertTrue(cmd.contains("gdigrab")); assertFalse(cmd.contains("title=My app"));
  assertTrue(String.join(" ",cmd).contains("crop=trunc(iw/2)*2:trunc(ih/2)*2"));
  assertTrue(cmd.contains("desktop")); assertFalse(cmd.contains("-crf"));
  assertEquals("8000000",cmd.get(cmd.indexOf("-b:v")+1));
 }
 @Test void secondaryMonitorUsesAbsoluteDesktopBounds() {
  var p=RecordingProfile.builder().source(CaptureSource.monitor("screen1","secondary",-1920,0,1920,1080,1))
    .outputFile(Path.of("out.mp4")).encoder(EncoderBackend.LIBX264).captureBackend(CaptureBackend.DDAGrab).build();
  var cmd=new FfmpegCommandBuilder(Path.of("ffmpeg.exe")).build(p);
  assertTrue(cmd.contains("gdigrab")); assertEquals("-1920", cmd.get(cmd.indexOf("-offset_x")+1));
  assertTrue(cmd.contains("1920x1080")); assertTrue(cmd.contains("desktop"));
 }
 @Test void gpuDesktopBoundsOnHost() {
  String engine=System.getProperty("preview.ffmpeg");
  org.junit.jupiter.api.Assumptions.assumeTrue(engine != null && System.getProperty("os.name").startsWith("Windows"));
  for (var source : java.util.List.of(CaptureSource.region(100,100,640,360),
          CaptureSource.window("visible",2660,606,640,360),
          CaptureSource.monitor("screen1","secondary",2560,506,1920,1080,1))) {
   var p=RecordingProfile.builder().source(source).outputFile(Path.of("out.mp4"))
     .encoder(EncoderBackend.LIBX264).captureBackend(CaptureBackend.DDAGrab).build();
   var cmd=new FfmpegCommandBuilder(Path.of(engine)).build(p);
   assertFalse(cmd.contains("gdigrab"));
   assertTrue(cmd.stream().anyMatch(a -> a.contains("desktop_coords=1") && a.contains(":offset_x="+source.x())));
  }
 }
 @Test void recordingBorderIsThinAndOutsideCapture() {
  var r = new javafx.geometry.Rectangle2D(100,200,800,600);
  var edges = org.firefly.screenrec.ui.overlay.RecordingBorderOverlay.edgeBounds(r);
  assertEquals(4, edges.size());
  for (var edge : edges) {
   assertTrue(edge.getWidth() == 2 || edge.getHeight() == 2);
   assertFalse(edge.getMinX() < r.getMaxX() && edge.getMaxX() > r.getMinX()
     && edge.getMinY() < r.getMaxY() && edge.getMaxY() > r.getMinY());
  }
 }
 @Test void mixedDpiCoordinatesKeepSeparateOrigins() {
  var s = new org.firefly.screenrec.core.screen.ScreenInfo(1,"screen1","secondary",2560,0,1920,1080,1920,1080,1,1,false,1706,0);
  assertEquals(2560,s.bounds().getMinX()); assertEquals(1706,s.visualBounds().getMinX());
  var r = org.firefly.screenrec.ui.RecordingController.regionCountdownBounds(new javafx.geometry.Rectangle2D(2660,100,800,600),s);
  assertEquals(1806,r.getMinX()); assertEquals(100,r.getMinY());
 }
 @Test void bitrateDefaultsAndBounds() {
  var s=new AppSettings(); assertEquals(0,s.getVideoBitrateMbps());
  s.setVideoBitrateMbps(-1); assertEquals(0,s.getVideoBitrateMbps());
  s.setVideoBitrateMbps(1000); assertEquals(200,s.getVideoBitrateMbps());
 }
 @Test void enumerateWindowsOnHost() throws Exception {
  if(System.getProperty("os.name").startsWith("Windows")) {
   for(var w:org.firefly.screenrec.core.screen.ApplicationWindowService.list()) {
    assertFalse(w.title().isBlank()); assertTrue(w.width()>1); assertTrue(w.height()>1);
   }
  }
 }
}
