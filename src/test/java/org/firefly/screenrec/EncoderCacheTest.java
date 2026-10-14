package org.firefly.screenrec;
import org.firefly.screenrec.core.recorder.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class EncoderCacheTest {
 @TempDir Path dir;
 @Test void validCacheAndEngineChange() {
  Path p=dir.resolve("cache"); var list=List.of(EncoderBackend.LIBX264);
  EncoderCache.write(p,"engine-a",list);
  assertEquals(list,EncoderCache.read(p,"engine-a",list));
  assertTrue(EncoderCache.read(p,"engine-b",list).isEmpty());
  assertTrue(EncoderCache.read(p,"engine-a",List.of(EncoderBackend.LIBX265)).isEmpty());
 }
 @Test void missingOrDamagedCacheIsOptional() throws Exception {
  Path p=dir.resolve("cache");
  assertTrue(EncoderCache.read(p,"a",List.of()).isEmpty());
  Files.writeString(p,"invalid");
  assertTrue(EncoderCache.read(p,"a",List.of()).isEmpty());
 }
}
