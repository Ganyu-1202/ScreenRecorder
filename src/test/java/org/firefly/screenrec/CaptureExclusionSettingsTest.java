package org.firefly.screenrec;
import org.junit.jupiter.api.Test;
import org.firefly.screenrec.core.settings.AppSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class CaptureExclusionSettingsTest {
 @Test void defaultEnabledForNewAndOldSettings() throws Exception {
  assertTrue(new AppSettings().isExcludeControlBarFromCapture());
  assertTrue(new ObjectMapper().readValue("{}", AppSettings.class).isExcludeControlBarFromCapture());
 }
 @Test void disabledPreferenceRoundTrips() throws Exception {
  var mapper=new ObjectMapper(); var settings=new AppSettings();
  settings.setExcludeControlBarFromCapture(false);
  assertFalse(mapper.readValue(mapper.writeValueAsString(settings),AppSettings.class).isExcludeControlBarFromCapture());
 }
}
