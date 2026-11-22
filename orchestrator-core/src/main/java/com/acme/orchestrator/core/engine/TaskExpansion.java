package com.acme.orchestrator.core.engine;

import com.acme.orchestrator.core.dag.Dag;
import com.acme.orchestrator.core.dag.TaskNode;
import com.acme.orchestrator.core.dag.WorkflowGraph;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.StageDefinition;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How a stage marked {@code expand: tasks} fans out: one sub-stage per task of the run's task graph
 * that changes code ({@code CODE}, {@code CONFIG}, {@code MIGRATION}; {@code TEST} and {@code DOC}
 * tasks belong to the test and docs stages). A sub-stage's key is {@code <stage>.<task>}, e.g.
 * {@code implementation.T3}; stage ids can't contain a dot, so keys never clash with stages.
 *
 * <p>Sub-stages keep the tasks' dependencies (on other expanded tasks). They share one workspace
 * and write whole files, so tasks whose {@code estimatedFiles} overlap are chained in task order;
 * tasks that touch different files run in parallel.
 *
 * <p>Recorded as a {@code STAGE_EXPANDED} event (the task list and the task graph it came from), so
 * recovery and the projections rebuild exactly the same graph from the log. No tasks means the
 * stage runs once, unexpanded.
 */
public record TaskExpansion(String stageId, String taskGraphId, List<Task> tasks) {

  /**
   * @param dependsOn ids of other tasks of this expansion that must finish first
   * @param files the task's {@code estimatedFiles}
   */
  public record Task(String id, String title, List<String> dependsOn, List<String> files) {
    public Task {
      Objects.requireNonNull(id, "id");
      title = title == null ? "" : title;
      dependsOn = List.copyOf(dependsOn);
      files = List.copyOf(files);
    }
  }

  /** Task types an implementation stage expands into. */
  public static final Set<String> EXPANDED_TYPES = Set.of("CODE", "CONFIG", "MIGRATION");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public TaskExpansion {
    Objects.requireNonNull(stageId, "stageId");
    Objects.requireNonNull(taskGraphId, "taskGraphId");
    tasks = List.copyOf(tasks);
  }

  /** The sub-stage key of {@code taskId} in {@code stageId}. */
  public static String key(String stageId, String taskId) {
    return stageId + "." + taskId;
  }

  /** The stage a key belongs to: the key itself, or the part before the dot of a sub-stage. */
  public static String stageIdOf(String key) {
    int dot = key.indexOf('.');
    return dot < 0 ? key : key.substring(0, dot);
  }

  /** The task of a sub-stage key, if it is one. */
  public static Optional<String> taskIdOf(String key) {
    int dot = key.indexOf('.');
    return dot < 0 ? Optional.empty() : Optional.of(key.substring(dot + 1));
  }

  public List<String> keys() {
    return tasks.stream().map(t -> key(stageId, t.id())).toList();
  }

  /** Plans the fan-out of {@code stageId} from a task graph artifact. */
  public static TaskExpansion plan(String stageId, Artifact taskGraph) {
    JsonNode graph = JSON.readTree(taskGraph.contentJson());
    List<JsonNode> eligible = new ArrayList<>();
    for (JsonNode t : graph.path("tasks")) {
      if (EXPANDED_TYPES.contains(t.path("type").asString(""))) {
        eligible.add(t);
      }
    }
    Set<String> ids = new HashSet<>();
    eligible.forEach(t -> ids.add(t.path("id").asString("")));
    List<Task> tasks = new ArrayList<>();
    for (JsonNode t : eligible) {
      List<String> deps = new ArrayList<>();
      t.path("dependsOn")
          .forEach(d -> {
            String dep = d.asString("");
            if (ids.contains(dep) && !deps.contains(dep)) {
              deps.add(dep); // dependencies on test or doc tasks don't block code
            }
          });
      List<String> files = new ArrayList<>();
      t.path("estimatedFiles").forEach(f -> files.add(f.asString("")));
      tasks.add(new Task(t.path("id").asString(""), t.path("title").asString(""), deps, files));
    }
    return new TaskExpansion(stageId, taskGraph.id(), chainOverlapping(tasks));
  }

  /**
   * Adds a dependency from each task on every earlier task that shares an estimated file with it,
   * unless it already depends on it (directly or transitively): so two writers of one file never
   * run at the same time.
   */
  static List<Task> chainOverlapping(List<Task> tasks) {
    Map<String, List<String>> deps = new LinkedHashMap<>();
    tasks.forEach(t -> deps.put(t.id(), new ArrayList<>(t.dependsOn())));
    for (int j = 0; j < tasks.size(); j++) {
      for (int i = 0; i < j; i++) {
        Task earlier = tasks.get(i);
        Task later = tasks.get(j);
        boolean overlap = earlier.files().stream().anyMatch(later.files()::contains);
        if (overlap && !reaches(deps, later.id(), earlier.id())) {
          deps.get(later.id()).add(earlier.id());
        }
      }
    }
    return tasks.stream()
        .map(t -> new Task(t.id(), t.title(), deps.get(t.id()), t.files()))
        .toList();
  }

  /** Whether {@code from} depends on {@code to}, directly or through other tasks. */
  private static boolean reaches(Map<String, List<String>> deps, String from, String to) {
    Deque<String> todo = new ArrayDeque<>(deps.getOrDefault(from, List.of()));
    Set<String> seen = new HashSet<>();
    while (!todo.isEmpty()) {
      String next = todo.pop();
      if (next.equals(to)) {
        return true;
      }
      if (seen.add(next)) {
        todo.addAll(deps.getOrDefault(next, List.of()));
      }
    }
    return false;
  }

  /** The STAGE_EXPANDED payload. */
  public Map<String, Object> payload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("taskGraph", taskGraphId);
    payload.put(
        "tasks",
        tasks.stream()
            .map(t -> {
              Map<String, Object> task = new LinkedHashMap<>();
              task.put("id", t.id());
              task.put("key", key(stageId, t.id()));
              task.put("title", t.title());
              task.put("dependsOn", t.dependsOn());
              task.put("files", t.files());
              return task;
            })
            .toList());
    return payload;
  }

  /** Reads a STAGE_EXPANDED event back. */
  @SuppressWarnings("unchecked")
  public static TaskExpansion fromEvent(Event e) {
    if (e.type() != EventType.STAGE_EXPANDED) {
      throw new IllegalArgumentException("not a STAGE_EXPANDED event: " + e.type());
    }
    List<Task> tasks = new ArrayList<>();
    for (Object o : (List<Object>) e.payload().getOrDefault("tasks", List.of())) {
      Map<String, Object> t = (Map<String, Object>) o;
      tasks.add(
          new Task(
              (String) t.get("id"),
              (String) t.get("title"),
              (List<String>) t.getOrDefault("dependsOn", List.of()),
              (List<String>) t.getOrDefault("files", List.of())));
    }
    return new TaskExpansion(
        e.stageId().orElseThrow(), e.payloadString("taskGraph").orElse(""), tasks);
  }

  /** The latest expansion of each stage in the log, by stage id. */
  public static Map<String, TaskExpansion> latest(List<Event> log) {
    Map<String, TaskExpansion> latest = new LinkedHashMap<>();
    for (Event e : log) {
      if (e.type() == EventType.STAGE_EXPANDED) {
        TaskExpansion x = fromEvent(e);
        latest.put(x.stageId(), x);
      }
    }
    return latest;
  }

  /** The run's stage graph: the workflow's, with the latest expansion of each stage applied. */
  public static Dag<String> graph(WorkflowDefinition workflow, Map<String, TaskExpansion> latest) {
    Dag<String> dag = WorkflowGraph.of(workflow);
    for (StageDefinition stage : workflow.stages()) {
      TaskExpansion x = latest.get(stage.id());
      if (x != null && !x.tasks().isEmpty()) {
        dag.expand(
            stage.id(),
            x.tasks().stream()
                .map(t ->
                    TaskNode.of(
                        key(stage.id(), t.id()),
                        t.dependsOn().stream().map(d -> key(stage.id(), d)).toList()))
                .toList());
      }
    }
    return dag;
  }

  /** The task's files and title, for the agent of a sub-stage. */
  public Optional<Task> task(String taskId) {
    return tasks.stream().filter(t -> t.id().equals(taskId)).findFirst();
  }

  /** Keys in the order of the tasks. */
  public Set<String> keySet() {
    return new LinkedHashSet<>(keys());
  }
}
