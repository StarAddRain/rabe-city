package city;

import java.nio.file.*;
import java.util.*;

/** One B-owned simulation clock. Page refresh and node start time never change it. */
public final class Motion {
  final Path file;
  long anchorWall;
  double offset;
  boolean paused;

  Motion(Path data) throws Exception {
    file = data.resolve("motion.json");
    if (Files.exists(file)) {
      Map<String, Object> m = Wire.read(file);
      anchorWall = ((Number) m.get("anchor")).longValue();
      offset = ((Number) m.get("offset")).doubleValue();
      paused = Boolean.TRUE.equals(m.get("paused"));
    } else {
      anchorWall = System.currentTimeMillis();
      save();
    }
  }

  double elapsed(long now) {
    return offset + (paused ? 0 : Math.max(0, now - anchorWall) / 1000.0);
  }

  synchronized Map<String, Object> snapshot() {
    long now = System.currentTimeMillis();
    return Json.obj(
        "elapsed",
        elapsed(now),
        "paused",
        paused,
        "serverTime",
        now,
        "tick",
        (long) (elapsed(now) * 10));
  }

  synchronized Map<String, Object> set(boolean value) throws Exception {
    long now = System.currentTimeMillis();
    double old = elapsed(now);
    offset = old;
    anchorWall = now;
    paused = value;
    save();
    return snapshot();
  }

  void save() throws Exception {
    Wire.save(file, Json.obj("anchor", anchorWall, "offset", offset, "paused", paused));
  }
}
