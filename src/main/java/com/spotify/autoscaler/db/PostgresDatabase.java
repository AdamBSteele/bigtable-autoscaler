/*-
 * -\-\-
 * bigtable-autoscaler
 * --
 * Copyright (C) 2018 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.autoscaler.db;

import static com.spotify.autoscaler.Main.APP_PREFIX;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.codahale.metrics.Gauge;
import com.google.common.collect.ImmutableMap;
import com.spotify.autoscaler.AutoscaleJob;
import com.spotify.metrics.core.SemanticMetricRegistry;
import com.typesafe.config.Config;
import com.zaxxer.hikari.HikariDataSource;

public class PostgresDatabase implements Database {

  private static final int MAX_POOL_SIZE = 16;

  private static final String[] COLUMNS =
      new String[]{ "project_id", "instance_id", "cluster_id", "min_nodes",
                    "max_nodes", "cpu_target", "overload_step", "last_change", "last_check",
                    "enabled", "last_failure",
                    "consecutive_failure_count", "last_failure_message" , "load_delta" };

  private static final String ALL_COLUMNS = String.join(", ", COLUMNS);

  private static final String SELECT_ALL_COLUMNS = "SELECT " + ALL_COLUMNS + " FROM autoscale";

  private final HikariDataSource dataSource;
  private final NamedParameterJdbcTemplate jdbc;
  private final SemanticMetricRegistry registry;

  public PostgresDatabase(Config config, SemanticMetricRegistry registry) {
    this.dataSource = dataSource(config);
    this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    this.registry = registry;
    registerMetricActiveConnections(this.registry);
  }

  private void registerMetricActiveConnections(SemanticMetricRegistry registry) {
    registry.register(APP_PREFIX.tagged("what", "open-db-connections"),
        (Gauge<Integer>) () -> this.dataSource.getHikariPoolMXBean().getTotalConnections());
  }

  private HikariDataSource dataSource(Config config) {
    final HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(config.getString("jdbcUrl"));
    ds.setUsername(config.getString("username"));
    ds.setPassword(config.getString("password"));
    ds.setMaximumPoolSize(MAX_POOL_SIZE);
    ds.setInitializationFailTimeout(-1);
    return ds;
  }

  @Override
  public void close() {
    registerMetricActiveConnections(this.registry);
    this.dataSource.close();
  }

  @Override
  public List<BigtableCluster> getBigtableClusters() {
    return getBigtableClusters(null, null, null);
  }

  @Override
  public Optional<BigtableCluster> getBigtableCluster(final String projectId,
                                                      final String instanceId,
                                                      final String clusterId) {
    final List<BigtableCluster> list = getBigtableClusters(projectId, instanceId, clusterId);
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  private BigtableCluster buildClusterFromResultSet(final ResultSet rs) throws SQLException {
    return new BigtableClusterBuilder()
        .projectId(rs.getString("project_id"))
        .instanceId(rs.getString("instance_id"))
        .clusterId(rs.getString("cluster_id"))
        .minNodes(rs.getInt("min_nodes"))
        .maxNodes(rs.getInt("max_nodes"))
        .cpuTarget(rs.getDouble("cpu_target"))
        .overloadStep(Optional.ofNullable((Integer) rs.getObject("overload_step")))
        .lastChange(Optional.ofNullable(rs.getTimestamp("last_change")).map(Timestamp::toInstant))
        .lastCheck(Optional.ofNullable(rs.getTimestamp("last_check")).map(Timestamp::toInstant))
        .enabled(rs.getBoolean("enabled"))
        .lastFailure(Optional.ofNullable(rs.getTimestamp("last_failure")).map(Timestamp::toInstant))
        .lastFailureMessage(Optional.ofNullable(rs.getString("last_failure_message")))
        .consecutiveFailureCount(rs.getInt("consecutive_failure_count"))
        .loadDelta(rs.getInt("load_delta"))
        .build();
  }

  @Override
  public List<BigtableCluster> getBigtableClusters(final String projectId,
                                                      final String instanceId,
                                                      final String clusterId) {
    final Map<String, String> args = new TreeMap<>();
    args.put("project_id", projectId);
    args.put("instance_id", instanceId);
    args.put("cluster_id", clusterId);
    return jdbc.getJdbcOperations()
        .query(
            selectClustersQuery(args), 
            (rs, rowNum) -> buildClusterFromResultSet(rs),
            args.values().toArray()
        );
  }

  private String selectClustersQuery(final Map<String, String> args) {
    final StringBuilder sql = new StringBuilder(SELECT_ALL_COLUMNS);
    args.values().removeIf(Objects::isNull);
    if (args.size() > 0) {
      sql.append(" WHERE ");
      sql.append(String.join(" AND ",
          // args.keys.map(key -> "$key = ?")
          args.keySet().stream().map(key -> key + " = ?").collect(Collectors.toList())));
    }
    return sql.toString();
  }

  private boolean upsertBigtableCluster(BigtableCluster cluster) {
    final String sql = "INSERT INTO "
        + "autoscale(project_id, instance_id, cluster_id, min_nodes, max_nodes, cpu_target, overload_step, enabled) "
        + "VALUES(:project_id, :instance_id, :cluster_id, :min_nodes, :max_nodes, :cpu_target, :overload_step, :enabled) "
        + "ON CONFLICT(project_id, instance_id, cluster_id) "
        + "DO UPDATE SET "
        + "min_nodes = :min_nodes, max_nodes = :max_nodes, cpu_target = :cpu_target, overload_step = :overload_step, enabled = :enabled";
    final Map<String, Object> params = new HashMap<String, Object>();
    params.put("project_id", cluster.projectId());
    params.put("instance_id", cluster.instanceId());
    params.put("cluster_id", cluster.clusterId());
    params.put("min_nodes", cluster.minNodes());
    params.put("max_nodes", cluster.maxNodes());
    params.put("cpu_target", cluster.cpuTarget());
    params.put("overload_step", cluster.overloadStep().orElse(null));
    params.put("enabled", cluster.enabled());
    return jdbc.update(sql, Collections.unmodifiableMap(params)) == 1;
  }

  @Override
  public boolean insertBigtableCluster(BigtableCluster cluster) {
    return upsertBigtableCluster(cluster);
  }

  @Override
  public boolean updateBigtableCluster(BigtableCluster cluster) {
    return upsertBigtableCluster(cluster);
  }

  @Override
  public boolean deleteBigtableCluster(String projectId, String instanceId, String clusterId) {
    final String sql = "DELETE FROM autoscale WHERE project_id = ? AND instance_id = ? AND cluster_id = ?";
    int numRowsUpdated = jdbc.getJdbcOperations().update(sql, projectId, instanceId, clusterId);
    return numRowsUpdated == 1;
  }

  @Override
  public boolean setLastChange(String projectId, String instanceId, String clusterId, Instant lastChange) {
    final String sql = "UPDATE autoscale SET last_change = ? WHERE project_id = ? AND instance_id = ? AND cluster_id = ?";
    int numRowsUpdated = jdbc.getJdbcOperations().update(sql, Timestamp.from(lastChange), projectId, instanceId, clusterId);
    return numRowsUpdated == 1;
  }

  /**
   * Fetch a cluster that we can autoscale.
   * To ensure other threads don't pick this cluster we update lastCheck in the same transaction and ignore cluster
   * where lastCheck is recent. This is done using "FOR UPDATE SKIP LOCKED" which also ensure other threads don't block.
   */
  @Override
  public Optional<BigtableCluster> getCandidateCluster() {
    final String sql = "UPDATE autoscale SET last_check = current_timestamp "
        + "WHERE (project_id, instance_id, cluster_id) = "
        + "(SELECT project_id, instance_id, cluster_id FROM autoscale "
        + "WHERE enabled=true AND coalesce(last_check,'epoch') < current_timestamp - CAST(:check_interval AS interval) "
        + "ORDER BY coalesce(last_check,'epoch') ASC LIMIT 1 FOR UPDATE SKIP LOCKED ) "
        + "RETURNING " + ALL_COLUMNS;

    final List<BigtableCluster> list = jdbc.query(sql,
      ImmutableMap.of("check_interval", AutoscaleJob.CHECK_INTERVAL.getSeconds() + " seconds"),
      (rs, rowNum) -> buildClusterFromResultSet(rs));
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  @Override
  public boolean clearFailureCount(String projectId, String instanceId, String clusterId) {
    final String sql = "UPDATE autoscale SET consecutive_failure_count = 0 WHERE project_id = ? AND instance_id = ? AND cluster_id = ?";
    return jdbc.getJdbcOperations().update(sql, projectId, instanceId, clusterId) == 1;
  }

  @Override
  public boolean increaseFailureCount(String projectId, String instanceId, String clusterId, Instant lastFailure,
      String lastFailureMessage) {
    final String sql = "UPDATE autoscale "
        + "SET last_failure = ?, consecutive_failure_count = consecutive_failure_count + 1, last_failure_message = ? "
        + "WHERE project_id = ? AND instance_id = ? AND cluster_id = ?";
    int numRowsUpdated = jdbc.getJdbcOperations().update(sql,
        Timestamp.from(lastFailure), lastFailureMessage, projectId, instanceId, clusterId);
    return numRowsUpdated == 1;
  }

  @Override
  public void logResize(ClusterResizeLog log) {
    final String sql = "INSERT INTO resize_log"
        + "(timestamp, project_id, instance_id, cluster_id, min_nodes, max_nodes, cpu_target, "
        + "overload_step, current_nodes, target_nodes, cpu_utilization, storage_utilization, detail, "
        + "success, error_message, load_delta) "
        + "VALUES "
        + "(:timestamp, :project_id, :instance_id, :cluster_id, :min_nodes, :max_nodes, :cpu_target, "
        + ":overload_step, :current_nodes, :target_nodes, :cpu_utilization, :storage_utilization, :detail, "
        + ":success, :error_message, :load_delta)";
    final Map<String, Object> params = new HashMap<String, Object>();
    params.put("timestamp", log.timestamp());
    params.put("project_id", log.projectId());
    params.put("instance_id", log.instanceId());
    params.put("cluster_id", log.clusterId());
    params.put("min_nodes", log.minNodes());
    params.put("max_nodes", log.maxNodes());
    params.put("cpu_target", log.cpuTarget());
    params.put("overload_step", log.overloadStep().orElse(null));
    params.put("current_nodes", log.currentNodes());
    params.put("target_nodes", log.targetNodes());
    params.put("cpu_utilization", log.cpuUtilization());
    params.put("storage_utilization", log.storageUtilization());
    params.put("detail", log.resizeReason());
    params.put("success", log.success());
    params.put("error_message", log.errorMessage().orElse(null));
    params.put("load_delta", log.loadDelta());
    jdbc.update(sql, Collections.unmodifiableMap(params));
  }

  @Override
  public long getDailyResizeCount() {
    final String sql = "SELECT COUNT(*) FROM RESIZE_LOG WHERE TIMESTAMP >= :midnight";
    return jdbc.queryForObject(sql, ImmutableMap.of("midnight", midnight()), Long.class);
  }

  private LocalDateTime midnight() {
    // GCP quotas are reset at midnight *Pacific Time*, resize timestamps are in UTC in DB
    final ZoneId gcpZone = ZoneId.of("America/Los_Angeles");
    final LocalDateTime localMidnight = LocalDateTime.of(LocalDate.now(gcpZone), LocalTime.MIDNIGHT);
    final ZonedDateTime gcpZoneMidnight = localMidnight.atZone(gcpZone);
    return gcpZoneMidnight.withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
  }

  @Override
  public void healthCheck() {
    jdbc.getJdbcOperations().execute("SELECT 1");
  }

  @Override
  public Collection<ClusterResizeLog> getLatestResizeEvents(String projectId, String instanceId, String clusterId) {
    final String sql = "SELECT "
        + "timestamp, project_id, instance_id, cluster_id, min_nodes, max_nodes, load_delta, cpu_target, overload_step, "
        + "current_nodes, target_nodes, cpu_utilization, storage_utilization, detail, success, error_message "
        + "FROM resize_log "
        + "WHERE project_id = :project_id AND instance_id = :instance_id AND cluster_id = :cluster_id "
        + "ORDER BY timestamp DESC "
        + "LIMIT 100";
    final Map<String, Object> params = new HashMap<String, Object>();
    params.put("project_id", projectId);
    params.put("instance_id", instanceId);
    params.put("cluster_id", clusterId);
    return jdbc.query(sql, params, (rs, rowNum) -> buildClusterResizeLogFromResultSet(rs));
  }

  private ClusterResizeLog buildClusterResizeLogFromResultSet(final ResultSet rs) throws SQLException {
    return new ClusterResizeLogBuilder()
        .timestamp(rs.getTimestamp("timestamp"))
        .projectId(rs.getString("project_id"))
        .instanceId(rs.getString("instance_id"))
        .clusterId(rs.getString("cluster_id"))
        .minNodes(rs.getInt("min_nodes"))
        .maxNodes(rs.getInt("max_nodes"))
        .cpuTarget(rs.getDouble("cpu_target"))
        .overloadStep(Optional.ofNullable((Integer) rs.getObject("overload_step")))
        .currentNodes(rs.getInt("current_nodes"))
        .targetNodes(rs.getInt("target_nodes"))
        .cpuUtilization(rs.getDouble("cpu_utilization"))
        .storageUtilization(rs.getDouble("storage_utilization"))
        .resizeReason(rs.getString("detail"))
        .success(rs.getBoolean("success"))
        .errorMessage(Optional.ofNullable((String) rs.getObject("error_message")))
        .loadDelta(rs.getInt("load_delta"))
        .build();
  }

  @Override
  public boolean updateLoadDelta(String projectId, String instanceId, String clusterId, Integer loadDelta) {
    final String sql = "UPDATE autoscale "
                       + "SET load_delta = ? "
                       + "WHERE project_id = ? AND instance_id = ? AND cluster_id = ?";
    int numRowsUpdated = jdbc.getJdbcOperations().update(sql, loadDelta, projectId, instanceId, clusterId);
    return numRowsUpdated == 1;
  }

}
