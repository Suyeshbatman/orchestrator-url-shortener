package com.acme.orchestrator.core.dag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Invariants over random DAGs. Edges only go from a lower to a higher rank, so the graph is acyclic
 * by construction; nodes are inserted in shuffled order so insertion order doesn't hide bugs.
 */
class DagPropertiesTest {

  record Graph(Dag<Integer> dag, List<int[]> edges) {}

  static Graph randomDag(int n, long seed) {
    Random random = new Random(seed);
    List<Integer> ranks = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ranks.add(i);
    }
    List<Integer> insertion = new ArrayList<>(ranks);
    Collections.shuffle(insertion, random);
    Dag<Integer> dag = new Dag<>();
    insertion.forEach(dag::addNode);
    List<int[]> edges = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      for (int j = i + 1; j < n; j++) {
        if (random.nextInt(4) == 0) {
          dag.addEdge(i, j);
          edges.add(new int[] {i, j});
        }
      }
    }
    return new Graph(dag, edges);
  }

  @Property(tries = 300)
  void topologicalOrderRespectsEveryEdge(
      @ForAll @IntRange(min = 1, max = 40) int n, @ForAll long seed) {
    Graph g = randomDag(n, seed);
    List<Integer> order = g.dag().topologicalOrder();

    assertThat(order).hasSize(n).doesNotHaveDuplicates();
    Map<Integer, Integer> position = new HashMap<>();
    for (int i = 0; i < order.size(); i++) {
      position.put(order.get(i), i);
    }
    for (int[] e : g.edges()) {
      assertThat(position.get(e[0])).isLessThan(position.get(e[1]));
    }
  }

  @Property(tries = 200)
  void eachNodeIsOneLevelBelowItsDeepestDependency(
      @ForAll @IntRange(min = 1, max = 40) int n, @ForAll long seed) {
    Dag<Integer> dag = randomDag(n, seed).dag();
    Map<Integer, Integer> level = new HashMap<>();
    List<List<Integer>> levels = dag.levels();
    for (int l = 0; l < levels.size(); l++) {
      for (Integer node : levels.get(l)) {
        level.put(node, l);
      }
    }

    assertThat(level).hasSize(n);
    for (Integer node : dag.nodes()) {
      int expected =
          dag.dependenciesOf(node).stream().mapToInt(d -> level.get(d) + 1).max().orElse(0);
      assertThat(level.get(node)).isEqualTo(expected);
    }
  }

  @Property(tries = 200)
  void closuresAreMirrorImages(@ForAll @IntRange(min = 1, max = 30) int n, @ForAll long seed) {
    Dag<Integer> dag = randomDag(n, seed).dag();
    for (Integer x : dag.nodes()) {
      for (Integer y : dag.downstreamClosure(x)) {
        assertThat(dag.upstreamClosure(y)).contains(x);
      }
      assertThat(dag.downstreamClosure(x)).doesNotContain(x);
    }
  }

  @Property(tries = 200)
  void addingABackEdgeAlongAPathCreatesADetectedCycle(
      @ForAll @IntRange(min = 2, max = 30) int n, @ForAll long seed) {
    Dag<Integer> dag = randomDag(n, seed).dag();
    for (int i = 0; i < n - 1; i++) {
      dag.addEdge(i, i + 1); // guarantee a path 0 → … → n-1
    }

    dag.addEdge(n - 1, 0);

    assertThatThrownBy(dag::topologicalOrder)
        .isInstanceOfSatisfying(
            CycleDetectedException.class,
            e -> {
              List<Object> cycle = e.cycle();
              assertThat(cycle.getFirst()).isEqualTo(cycle.getLast());
              for (int i = 0; i + 1 < cycle.size(); i++) {
                assertThat(dag.dependentsOf((Integer) cycle.get(i)))
                    .contains((Integer) cycle.get(i + 1));
              }
            });
  }

  @Property(tries = 200)
  void expansionPreservesOrderingAroundTheExpandedNode(
      @ForAll @IntRange(min = 1, max = 25) int n,
      @ForAll long seed,
      @ForAll @IntRange(min = 1, max = 6) int taskCount) {
    Dag<Integer> dag = randomDag(n, seed).dag();
    int target = new Random(seed).nextInt(n);
    List<Integer> upstream = List.copyOf(dag.dependenciesOf(target));
    List<Integer> downstream = List.copyOf(dag.dependentsOf(target));

    // A chain of tasks with ids beyond the node range.
    List<TaskNode<Integer>> tasks = new ArrayList<>();
    for (int t = 0; t < taskCount; t++) {
      int id = 1_000 + t;
      tasks.add(TaskNode.of(id, t == 0 ? List.of() : List.of(id - 1)));
    }
    dag.expand(target, tasks);

    List<Integer> order = dag.topologicalOrder();
    for (Integer u : upstream) {
      for (TaskNode<Integer> t : tasks) {
        assertThat(order.indexOf(u)).isLessThan(order.indexOf(t.id()));
      }
    }
    for (Integer d : downstream) {
      for (TaskNode<Integer> t : tasks) {
        assertThat(order.indexOf(t.id())).isLessThan(order.indexOf(d));
      }
    }
    assertThat(dag.size()).isEqualTo(n - 1 + taskCount);
  }
}
