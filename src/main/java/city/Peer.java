package city;

import java.nio.file.*;
import java.util.*;
import rabe.*;

/** Public context/helper cache. Block IDs are immutable, including after deregistration. */
public final class Peer {
  final String curator, token;
  final Path params;
  volatile List<Scheme> levels;
  volatile Map<String, Object> context;
  final Map<String, Map<String, Object>> blocks = new HashMap<>();

  Peer(String curator, String token, Path params) {
    this.curator = curator;
    this.token = token;
    this.params = params;
  }

  Map<String, Object> call(String path, Map<String, Object> b) throws Exception {
    return Client.request(curator + "/node" + path, token, b);
  }

  synchronized void init() throws Exception {
    if (context == null) {
      Map<String, Object> c = call("/context", Json.obj());
      levels = Compiler6.contexts(c, params);
      context = c;
    }
  }

  Map<String, Object> view() throws Exception {
    return call("/view", Json.obj());
  }

  synchronized Map<String, Object> block(String id) throws Exception {
    if (!blocks.containsKey(id)) blocks.put(id, call("/block", Json.obj("id", id)));
    return blocks.get(id);
  }

  Scheme.Registry registry(String id) throws Exception {
    Map<String, Object> b = block(id);
    return Wire.registry(levels.get(Json.num(b, "level")), Json.map(b.get("registry")));
  }

  Map<String, Object> vehicle(int id) throws Exception {
    return Compiler6.vehicle(view(), id);
  }
}
