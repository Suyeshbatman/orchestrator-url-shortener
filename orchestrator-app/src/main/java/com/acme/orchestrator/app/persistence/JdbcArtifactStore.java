package com.acme.orchestrator.app.persistence;

import com.acme.orchestrator.core.artifact.ArtifactDraft;
import com.acme.orchestrator.core.artifact.ArtifactStore;
import com.acme.orchestrator.core.artifact.InMemoryArtifactStore;
import com.acme.orchestrator.core.artifact.Lineage;
import com.acme.orchestrator.core.dag.Dag;
import com.acme.orchestrator.core.event.CanonicalJson;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.ArtifactRef;
import com.acme.orchestrator.core.model.ProducedBy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Versioned artifacts in PostgreSQL, with the same rules as the in-memory store: ids {@code
 * run:stage:type:vN}, inputs must match stored artifacts exactly, content kept byte for byte
 * (content_text; the jsonb column is derived from it).
 */
public final class JdbcArtifactStore implements ArtifactStore {

  private static final String COLUMNS =
      "id, run_id, stage_id, type, version, content_text, content_hash, produced_by, lineage,"
          + " rationale, created_at";

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  public JdbcArtifactStore(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tx = Objects.requireNonNull(tx, "tx");
    // PostgreSQL keeps microseconds: stamp with that precision so what save returns is what a
    // later read returns
    this.clock = Clock.tick(Objects.requireNonNull(clock, "clock"), Duration.ofNanos(1_000));
  }

  @Override
  public Artifact save(ArtifactDraft d) {
    return tx.execute(status -> {
      jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 2))")
          .param("key", d.runId() + "\n" + d.stageId() + "\n" + d.type())
          .query((rs, n) -> 1)
          .single();
      for (ArtifactRef in : d.inputs()) {
        Artifact stored =
            get(in.artifactId())
                .orElseThrow(() ->
                    new IllegalArgumentException("unknown input artifact " + in.artifactId()));
        if (!stored.ref().equals(in)) {
          throw new IllegalArgumentException(
              "input "
                  + in.artifactId()
                  + " does not match the stored artifact (type/version/hash)");
        }
      }
      int version =
          jdbc.sql(
                  "select coalesce(max(version), 0) + 1 from artifact"
                      + " where run_id = :run and stage_id = :stage and type = :type")
              .param("run", d.runId())
              .param("stage", d.stageId())
              .param("type", d.type())
              .query(Integer.class)
              .single();
      Artifact a =
          Artifact.create(
              InMemoryArtifactStore.id(d.runId(), d.stageId(), d.type(), version),
              d.runId(),
              d.stageId(),
              d.type(),
              version,
              d.contentJson(),
              d.producedBy(),
              d.inputs(),
              d.rationale(),
              clock);
      jdbc.sql(
              "insert into artifact ("
                  + COLUMNS
                  + ") values (:id, :run, :stage, :type, :version,"
                  + " :content, :hash, cast(:producedBy as jsonb), cast(:lineage as jsonb),"
                  + " :rationale, :created)")
          .param("id", a.id())
          .param("run", a.runId())
          .param("stage", a.stageId())
          .param("type", a.type())
          .param("version", a.version())
          .param("content", a.contentJson())
          .param("hash", a.contentHash())
          .param("producedBy", producedBy(a.producedBy()))
          .param("lineage", lineage(a.inputs()))
          .param("rationale", a.rationale())
          .param("created", Json.ts(a.createdAt()))
          .update();
      return a;
    });
  }

  @Override
  public Optional<Artifact> get(String artifactId) {
    return jdbc.sql("select " + COLUMNS + " from artifact where id = :id")
        .param("id", artifactId)
        .query(JdbcArtifactStore::artifact)
        .optional();
  }

  @Override
  public Optional<Artifact> latest(String runId, String stageId, String type) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from artifact where run_id = :run and stage_id = :stage"
                + " and type = :type order by version desc limit 1")
        .param("run", runId)
        .param("stage", stageId)
        .param("type", type)
        .query(JdbcArtifactStore::artifact)
        .optional();
  }

  @Override
  public Optional<Artifact> latestOfType(String runId, String type) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from artifact where run_id = :run and type = :type"
                + " order by seq desc limit 1")
        .param("run", runId)
        .param("type", type)
        .query(JdbcArtifactStore::artifact)
        .optional();
  }

  @Override
  public List<Artifact> versions(String runId, String stageId, String type) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from artifact where run_id = :run and stage_id = :stage"
                + " and type = :type order by version")
        .param("run", runId)
        .param("stage", stageId)
        .param("type", type)
        .query(JdbcArtifactStore::artifact)
        .list();
  }

  @Override
  public List<Artifact> byRun(String runId) {
    return jdbc.sql("select " + COLUMNS + " from artifact where run_id = :run order by seq")
        .param("run", runId)
        .query(JdbcArtifactStore::artifact)
        .list();
  }

  @Override
  public Lineage lineage(String artifactId) {
    Artifact root =
        get(artifactId)
            .orElseThrow(() -> new IllegalArgumentException("unknown artifact " + artifactId));
    Map<String, Artifact> seen = new LinkedHashMap<>();
    Dag<String> graph = new Dag<>();
    Deque<Artifact> todo = new ArrayDeque<>(List.of(root));
    seen.put(root.id(), root);
    graph.addNode(root.id());
    while (!todo.isEmpty()) {
      Artifact a = todo.poll();
      for (ArtifactRef in : a.inputs()) {
        Artifact input =
            seen.containsKey(in.artifactId())
                ? seen.get(in.artifactId())
                : get(in.artifactId()).orElseThrow();
        graph.addEdge(input.id(), a.id());
        if (seen.putIfAbsent(input.id(), input) == null) {
          todo.add(input);
        }
      }
    }
    return new Lineage(root, seen, graph);
  }

  private static String producedBy(ProducedBy p) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("agent", p.agent());
    p.model().ifPresent(m -> json.put("model", m));
    p.promptHash().ifPresent(h -> json.put("promptHash", h));
    return CanonicalJson.write(json);
  }

  private static String lineage(List<ArtifactRef> inputs) {
    List<Map<String, Object>> refs = new ArrayList<>();
    for (ArtifactRef r : inputs) {
      refs.add(
          Map.of(
              "artifactId", r.artifactId(),
              "type", r.type(),
              "version", r.version(),
              "contentHash", r.contentHash()));
    }
    return CanonicalJson.write(refs);
  }

  private static Artifact artifact(ResultSet rs, int row) throws SQLException {
    Map<String, Object> by = Json.map(rs.getString("produced_by"));
    ProducedBy producedBy =
        new ProducedBy(
            (String) by.get("agent"),
            Optional.ofNullable((String) by.get("model")),
            Optional.ofNullable((String) by.get("promptHash")));
    List<ArtifactRef> inputs = new ArrayList<>();
    for (Object o : Json.MAPPER.readValue(rs.getString("lineage"), List.class)) {
      Map<?, ?> r = (Map<?, ?>) o;
      inputs.add(
          new ArtifactRef(
              (String) r.get("artifactId"),
              (String) r.get("type"),
              ((Number) r.get("version")).intValue(),
              (String) r.get("contentHash")));
    }
    return new Artifact(
        rs.getString("id"),
        rs.getString("run_id"),
        rs.getString("stage_id"),
        rs.getString("type"),
        rs.getInt("version"),
        rs.getString("content_text"),
        rs.getString("content_hash"),
        producedBy,
        inputs,
        rs.getString("rationale"),
        Json.instant(rs, "created_at"));
  }
}
