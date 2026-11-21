package com.acme.orchestrator.core.dag;

import com.acme.orchestrator.core.model.StageStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A directed graph of work. An edge {@code from → to} means {@code from} must finish before {@code
 * to} starts ({@code to} depends on {@code from}). Adjacency lists in both directions make
 * dependents and dependencies O(1) to look up. Insertion order is kept everywhere, so every result
 * is deterministic (replays and tests depend on that).
 *
 * <p>Not thread-safe; the engine's single coordinator thread owns it.
 */
public final class Dag<T> {

  private final Map<T, Set<T>> out = new LinkedHashMap<>(); // node → dependents
  private final Map<T, Set<T>> in = new LinkedHashMap<>(); // node → dependencies

  public Dag<T> addNode(T node) {
    Objects.requireNonNull(node, "node");
    out.computeIfAbsent(node, k -> new LinkedHashSet<>());
    in.computeIfAbsent(node, k -> new LinkedHashSet<>());
    return this;
  }

  /** {@code to} depends on {@code from}. Adds missing nodes. Cycles are reported by the queries. */
  public Dag<T> addEdge(T from, T to) {
    if (Objects.equals(from, to)) {
      throw new CycleDetectedException(List.of(from, to));
    }
    addNode(from);
    addNode(to);
    out.get(from).add(to);
    in.get(to).add(from);
    return this;
  }

  public boolean contains(T node) {
    return out.containsKey(node);
  }

  public int size() {
    return out.size();
  }

  /** In insertion order. */
  public List<T> nodes() {
    return List.copyOf(out.keySet());
  }

  public Set<T> dependenciesOf(T node) {
    return Collections.unmodifiableSet(require(in, node));
  }

  public Set<T> dependentsOf(T node) {
    return Collections.unmodifiableSet(require(out, node));
  }

  /** An independent copy. */
  public Dag<T> copy() {
    Dag<T> copy = new Dag<>();
    out.keySet().forEach(copy::addNode);
    out.forEach((from, tos) -> tos.forEach(to -> copy.addEdge(from, to)));
    return copy;
  }

  /**
   * Kahn's algorithm, O(V + E): repeatedly take a node with no unfinished dependencies. Ties go to
   * insertion order.
   *
   * @throws CycleDetectedException naming one cycle (found by DFS), if not every node was emitted
   */
  public List<T> topologicalOrder() {
    Map<T, Integer> remaining = new HashMap<>();
    Deque<T> ready = new ArrayDeque<>();
    for (T node : out.keySet()) {
      int deps = in.get(node).size();
      remaining.put(node, deps);
      if (deps == 0) {
        ready.add(node);
      }
    }
    List<T> order = new ArrayList<>(out.size());
    while (!ready.isEmpty()) {
      T node = ready.poll();
      order.add(node);
      for (T dependent : out.get(node)) {
        if (remaining.merge(dependent, -1, Integer::sum) == 0) {
          ready.add(dependent);
        }
      }
    }
    if (order.size() != out.size()) {
      throw new CycleDetectedException(findCycle().orElseThrow());
    }
    return order;
  }

  /** Throws {@link CycleDetectedException} if there is a cycle. */
  public void validateAcyclic() {
    topologicalOrder();
  }

  /**
   * Parallel layers: level 0 has no dependencies, and each node sits one level below its deepest
   * dependency. Nodes in one level can run concurrently.
   */
  public List<List<T>> levels() {
    Map<T, Integer> level = new HashMap<>();
    List<List<T>> levels = new ArrayList<>();
    for (T node : topologicalOrder()) {
      int l = 0;
      for (T dep : in.get(node)) {
        l = Math.max(l, level.get(dep) + 1);
      }
      level.put(node, l);
      if (levels.size() == l) {
        levels.add(new ArrayList<>());
      }
      levels.get(l).add(node);
    }
    return levels.stream().map(List::copyOf).toList();
  }

  /**
   * Nodes that can be scheduled now: still PENDING (a missing entry counts as PENDING) and every
   * dependency SUCCEEDED or SKIPPED. In insertion order.
   */
  public List<T> readyNodes(Map<T, StageStatus> status) {
    List<T> ready = new ArrayList<>();
    for (T node : out.keySet()) {
      if (status.getOrDefault(node, StageStatus.PENDING) != StageStatus.PENDING) {
        continue;
      }
      boolean satisfied = true;
      for (T dep : in.get(node)) {
        StageStatus s = status.getOrDefault(dep, StageStatus.PENDING);
        if (!s.satisfiesDependency()) {
          satisfied = false;
          break;
        }
      }
      if (satisfied) {
        ready.add(node);
      }
    }
    return ready;
  }

  /** Everything that (transitively) depends on {@code node}, excluding it. BFS order. */
  public Set<T> downstreamClosure(T node) {
    return closure(node, out);
  }

  /** Everything {@code node} (transitively) depends on, excluding it. BFS order. */
  public Set<T> upstreamClosure(T node) {
    return closure(node, in);
  }

  /**
   * Replaces {@code node} with a subgraph. Roots of the subgraph (tasks with no internal
   * dependencies) inherit {@code node}'s dependencies; sinks (tasks nothing internal depends on)
   * feed {@code node}'s dependents. All or nothing: on any error the graph is unchanged.
   *
   * @throws IllegalArgumentException for an unknown node, no tasks, duplicate or clashing task ids,
   *     or an internal dependency on a task that isn't in the list
   * @throws CycleDetectedException if the result would have a cycle
   */
  public void expand(T node, List<TaskNode<T>> tasks) {
    require(out, node);
    if (tasks.isEmpty()) {
      throw new IllegalArgumentException("expansion of " + node + " needs at least one task");
    }
    Set<T> ids = new LinkedHashSet<>();
    for (TaskNode<T> t : tasks) {
      if (!ids.add(t.id())) {
        throw new IllegalArgumentException("duplicate task id " + t.id());
      }
      if (!t.id().equals(node) && contains(t.id())) {
        throw new IllegalArgumentException("task id " + t.id() + " already exists in the graph");
      }
    }
    Set<T> hasInternalDependents = new HashSet<>();
    for (TaskNode<T> t : tasks) {
      for (T dep : t.dependsOn()) {
        if (!ids.contains(dep)) {
          throw new IllegalArgumentException(
              "task " + t.id() + " depends on " + dep + ", which is not in this expansion");
        }
        hasInternalDependents.add(dep);
      }
    }

    Dag<T> next = copy();
    Set<T> upstream = new LinkedHashSet<>(in.get(node));
    Set<T> downstream = new LinkedHashSet<>(out.get(node));
    next.remove(node);
    for (TaskNode<T> t : tasks) {
      next.addNode(t.id());
    }
    for (TaskNode<T> t : tasks) {
      if (t.dependsOn().isEmpty()) {
        upstream.forEach(u -> next.addEdge(u, t.id()));
      }
      for (T dep : t.dependsOn()) {
        next.addEdge(dep, t.id());
      }
      if (!hasInternalDependents.contains(t.id())) {
        downstream.forEach(d -> next.addEdge(t.id(), d));
      }
    }
    next.validateAcyclic();

    out.clear();
    in.clear();
    out.putAll(next.out);
    in.putAll(next.in);
  }

  // ---- internals ----------------------------------------------------------------------------

  private void remove(T node) {
    for (T dep : in.remove(node)) {
      out.get(dep).remove(node);
    }
    for (T dependent : out.remove(node)) {
      in.get(dependent).remove(node);
    }
  }

  private static <T> Set<T> closure(T start, Map<T, Set<T>> edges) {
    require(edges, start);
    Set<T> seen = new LinkedHashSet<>();
    Deque<T> todo = new ArrayDeque<>(edges.get(start));
    while (!todo.isEmpty()) {
      T next = todo.poll();
      if (seen.add(next)) {
        todo.addAll(edges.get(next));
      }
    }
    return seen;
  }

  /**
   * One cycle in edge direction: recursive DFS with an on-stack set. Graphs here are a workflow's
   * stages plus expanded tasks (tens of nodes), so recursion depth is not a concern.
   */
  private Optional<List<T>> findCycle() {
    Set<T> done = new HashSet<>();
    for (T start : out.keySet()) {
      Optional<List<T>> cycle = dfs(start, done, new LinkedHashSet<>());
      if (cycle.isPresent()) {
        return cycle;
      }
    }
    return Optional.empty();
  }

  private Optional<List<T>> dfs(T node, Set<T> done, LinkedHashSet<T> onStack) {
    if (done.contains(node)) {
      return Optional.empty();
    }
    if (onStack.contains(node)) {
      List<T> cycle = new ArrayList<>();
      boolean inCycle = false;
      for (T s : onStack) {
        inCycle |= s.equals(node);
        if (inCycle) {
          cycle.add(s);
        }
      }
      cycle.add(node);
      return Optional.of(cycle);
    }
    onStack.add(node);
    for (T next : out.get(node)) {
      Optional<List<T>> cycle = dfs(next, done, onStack);
      if (cycle.isPresent()) {
        return cycle;
      }
    }
    onStack.remove(node);
    done.add(node);
    return Optional.empty();
  }

  private static <T> Set<T> require(Map<T, Set<T>> map, T node) {
    Set<T> edges = map.get(node);
    if (edges == null) {
      throw new IllegalArgumentException("unknown node " + node);
    }
    return edges;
  }

  @Override
  public String toString() {
    return "Dag" + out;
  }
}
