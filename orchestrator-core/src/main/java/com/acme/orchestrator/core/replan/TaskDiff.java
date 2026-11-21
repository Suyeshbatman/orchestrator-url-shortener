package com.acme.orchestrator.core.replan;

import com.acme.orchestrator.core.event.CanonicalJson;
import com.acme.orchestrator.core.model.Hashing;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How a new task graph differs from the old one, task by task (by id; a task "changed" when its
 * content differs, compared canonically so key order and whitespace don't count). When task
 * expansion lands (Phase 5), the implementation subgraph is re-expanded from this: tasks in {@code
 * unchanged} that SUCCEEDED are kept, {@code changed} and {@code removed} are invalidated, {@code
 * added} are scheduled.
 */
public record TaskDiff(
    List<String> unchanged, List<String> changed, List<String> removed, List<String> added) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public TaskDiff {
    unchanged = List.copyOf(unchanged);
    changed = List.copyOf(changed);
    removed = List.copyOf(removed);
    added = List.copyOf(added);
  }

  /** Diffs two {@code task_graph} artifacts ({@code {"tasks": [{"id": ...}, ...]}}). */
  public static TaskDiff of(String beforeJson, String afterJson) {
    Map<String, String> before = taskHashes(beforeJson);
    Map<String, String> after = taskHashes(afterJson);
    List<String> unchanged = new ArrayList<>();
    List<String> changed = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    List<String> added = new ArrayList<>();
    before.forEach((id, hash) -> {
      if (!after.containsKey(id)) {
        removed.add(id);
      } else if (after.get(id).equals(hash)) {
        unchanged.add(id);
      } else {
        changed.add(id);
      }
    });
    after.keySet().stream().filter(id -> !before.containsKey(id)).forEach(added::add);
    return new TaskDiff(unchanged, changed, removed, added);
  }

  public boolean sameTasks() {
    return changed.isEmpty() && removed.isEmpty() && added.isEmpty();
  }

  /** Task id → SHA-256 of its canonical JSON. */
  private static Map<String, String> taskHashes(String taskGraphJson) {
    JsonNode tasks = JSON.readTree(taskGraphJson).path("tasks");
    if (!tasks.isArray()) {
      throw new IllegalArgumentException("a task graph needs a 'tasks' list");
    }
    Map<String, String> hashes = new LinkedHashMap<>();
    for (JsonNode task : tasks) {
      JsonNode id = task.path("id");
      if (!id.isString() || id.stringValue().isBlank()) {
        throw new IllegalArgumentException("every task needs an id");
      }
      Object value = JSON.convertValue(task, Object.class);
      if (hashes.put(id.stringValue(), Hashing.sha256Hex(CanonicalJson.write(value))) != null) {
        throw new IllegalArgumentException("duplicate task id " + id.stringValue());
      }
    }
    return hashes;
  }
}
