package com.hpcloud.mon.persister.repository;

import com.codahale.metrics.Timer;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.hpcloud.mon.persister.configuration.MonPersisterConfiguration;
import io.dropwizard.setup.Environment;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.PreparedBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

public class VerticaMetricRepository extends VerticaRepository {

    private static final Logger logger = LoggerFactory.getLogger(VerticaMetricRepository.class);

    private final MonPersisterConfiguration configuration;
    private final Environment environment;

    private final Cache<Sha1HashId, Sha1HashId> definitionsIdCache;
    private final Cache<Sha1HashId, Sha1HashId> dimensionsIdCache;
    private final Cache<Sha1HashId, Sha1HashId> definitionDimensionsIdCache;

    private final Set<Sha1HashId> definitionIdSet = new HashSet<>();
    private final Set<Sha1HashId> dimensionIdSet = new HashSet<>();
    private final Set<Sha1HashId> definitionDimensionsIdSet = new HashSet<>();

    private static final String SQL_INSERT_INTO_METRICS =
            "insert into MonMetrics.measurements (definition_dimensions_id, time_stamp, value) values (:definition_dimension_id, :time_stamp, :value)";

    private static final String DEFINITIONS_TEMP_STAGING_TABLE = "(" +
            "   id BINARY(20) NOT NULL," +
            "   name VARCHAR(255) NOT NULL," +
            "   tenant_id VARCHAR(14) NOT NULL," +
            "   region VARCHAR(255) NOT NULL" +
            ")";

    private static final String DIMENSIONS_TEMP_STAGING_TABLE = "(" +
            "    dimension_set_id BINARY(20) NOT NULL," +
            "    name VARCHAR(255) NOT NULL," +
            "    value VARCHAR(255) NOT NULL" +
            ")";

    private static final String DEFINITIONS_DIMENSIONS_TEMP_STAGING_TABLE = "(" +
            "   id BINARY(20) NOT NULL," +
            "   definition_id BINARY(20) NOT NULL, " +
            "   dimension_set_id BINARY(20) NOT NULL " +
            ")";

    private PreparedBatch metricsBatch;
    private PreparedBatch stagedDefinitionsBatch;
    private PreparedBatch stagedDimensionsBatch;
    private PreparedBatch stageddefinitionDimensionsBatch;

    private final String definitionsTempStagingTableName;
    private final String dimensionsTempStagingTableName;
    private final String definitionDimensionsTempStagingTableName;

    private final String definitionsTempStagingTableInsertStmt;
    private final String dimensionsTempStagingTableInsertStmt;
    private final String definitionDimensionsTempStagingTableInsertStmt;

    private final Timer commitTimer;
    private final Timer flushTimer;

    @Inject
    public VerticaMetricRepository(DBI dbi, MonPersisterConfiguration configuration,
                                   Environment environment) throws NoSuchAlgorithmException, SQLException {
        super(dbi);
        logger.debug("Instantiating: " + this);

        this.configuration = configuration;
        this.environment = environment;
        this.commitTimer = this.environment.metrics().timer(this.getClass().getName() + "." + "commits-timer");
        this.flushTimer = this.environment.metrics().timer(this.getClass().getName() + "." + "staging-tables-flushed-timer");

        definitionsIdCache = CacheBuilder.newBuilder()
                .maximumSize(configuration.getVerticaMetricRepositoryConfiguration().getMaxCacheSize()).build();
        dimensionsIdCache = CacheBuilder.newBuilder().maximumSize(configuration.getVerticaMetricRepositoryConfiguration().getMaxCacheSize()).build();
        definitionDimensionsIdCache = CacheBuilder.newBuilder().maximumSize(configuration.getVerticaMetricRepositoryConfiguration().getMaxCacheSize()).build();

        logger.info("Building temp staging tables...");

        String uniqueName = this.toString().replaceAll("\\.", "_").replaceAll("\\@", "_");
        this.definitionsTempStagingTableName = uniqueName + "_staged_definitions";
        logger.debug("temp staging definitions table name: " + definitionsTempStagingTableName);

        this.dimensionsTempStagingTableName = uniqueName + "_staged_dimensions";
        logger.debug("temp staging dimensions table name:" + dimensionsTempStagingTableName);

        this.definitionDimensionsTempStagingTableName = uniqueName + "_staged_definitions_dimensions";
        logger.debug("temp staging definitionDimensions table name: " + definitionDimensionsTempStagingTableName);

        this.definitionsTempStagingTableInsertStmt = "insert into  MonMetrics.Definitions select distinct * from " + definitionsTempStagingTableName + " where id not in (select id from MonMetrics.Definitions)";
        logger.debug("definitions insert stmt: " + definitionsTempStagingTableInsertStmt);

        this.dimensionsTempStagingTableInsertStmt = "insert into MonMetrics.Dimensions select distinct * from " + dimensionsTempStagingTableName + " where dimension_set_id not in (select dimension_set_id from MonMetrics.Dimensions)";
        logger.debug("dimensions insert stmt: " + definitionsTempStagingTableInsertStmt);

        this.definitionDimensionsTempStagingTableInsertStmt = "insert into MonMetrics.definitionDimensions select distinct * from " + definitionDimensionsTempStagingTableName + " where id not in (select id from MonMetrics.definitionDimensions)";
        logger.debug("definitionDimensions insert stmt: " + definitionDimensionsTempStagingTableInsertStmt);

        handle.execute("drop table if exists " + definitionsTempStagingTableName + " cascade");
        handle.execute("drop table if exists " + dimensionsTempStagingTableName + " cascade");
        handle.execute("drop table if exists " + definitionDimensionsTempStagingTableName + " cascade");

        handle.execute("create local temp table " + definitionsTempStagingTableName + " " + DEFINITIONS_TEMP_STAGING_TABLE + " on commit preserve rows");
        handle.execute("create local temp table " + dimensionsTempStagingTableName + " " + DIMENSIONS_TEMP_STAGING_TABLE + " on commit preserve rows");
        handle.execute("create local temp table " + definitionDimensionsTempStagingTableName + " " + DEFINITIONS_DIMENSIONS_TEMP_STAGING_TABLE + " on commit preserve rows");

        handle.getConnection().setAutoCommit(false);
        metricsBatch = handle.prepareBatch(SQL_INSERT_INTO_METRICS);
        stagedDefinitionsBatch = handle.prepareBatch("insert into " + definitionsTempStagingTableName + " values (:id, :name, :tenant_id, :region)");
        stagedDimensionsBatch = handle.prepareBatch("insert into " + dimensionsTempStagingTableName + " values (:dimension_set_id, :name, :value)");
        stageddefinitionDimensionsBatch = handle.prepareBatch("insert into " + definitionDimensionsTempStagingTableName + " values (:id, :definition_id, :dimension_set_id)");
        handle.begin();
    }

    public void addToBatchMetrics(Sha1HashId defDimsId, String timeStamp, double value) {
        logger.debug("Adding metric to batch: defDimsId: {}, timeStamp: {}, value: {}", defDimsId.toHexString(), timeStamp, value);
        metricsBatch.add().bind("definition_dimension_id", defDimsId.getSha1Hash()).bind("time_stamp", timeStamp).bind("value", value);
    }

    public void addToBatchStagingDefinitions(Sha1HashId defId, String name, String tenantId, String region) {
        if (definitionsIdCache.getIfPresent(defId) == null) {
            logger.debug("Adding definition to batch: defId: {}, name: {}, tenantId: {}, region: {}", defId.toHexString(), name, tenantId, region);
            stagedDefinitionsBatch.add().bind("id", defId.getSha1Hash()).bind("name", name).bind("tenant_id", tenantId).bind("region", region);
            definitionIdSet.add(defId);
        }
    }

    public void addToBatchStagingDimensions(Sha1HashId dimSetId, String name, String value) {
        if (dimensionsIdCache.getIfPresent(dimSetId) == null) {
            logger.debug("Adding dimension to batch: dimSetId: {}, name: {}, value: {}", dimSetId.toHexString(), name, value);
            stagedDimensionsBatch.add().bind("dimension_set_id", dimSetId.getSha1Hash())
                    .bind("name", name)
                    .bind("value", value);
            dimensionIdSet.add(dimSetId);
        }
    }

    public void addToBatchStagingdefinitionDimensions(Sha1HashId defDimsId, Sha1HashId defId, Sha1HashId dimId) {
        if (definitionDimensionsIdCache.getIfPresent(defDimsId) == null) {
            logger.debug("Adding definitionDimension to batch: defDimsId: {}, defId: {}, dimId: {}", defDimsId.toHexString(), defId, dimId);
            stageddefinitionDimensionsBatch.add().bind("id", defDimsId.getSha1Hash()).bind("definition_id", defId.getSha1Hash()).bind("dimension_set_id", dimId.getSha1Hash());
            definitionDimensionsIdSet.add(defDimsId);
        }

    }

    public void flush() {
        try {
            commitBatch();
            long startTime = System.currentTimeMillis();
            Timer.Context context = flushTimer.time();
            handle.execute(definitionsTempStagingTableInsertStmt);
            handle.execute("truncate table " + definitionsTempStagingTableName);
            handle.execute(dimensionsTempStagingTableInsertStmt);
            handle.execute("truncate table " + dimensionsTempStagingTableName);
            handle.execute(definitionDimensionsTempStagingTableInsertStmt);
            handle.execute("truncate table " + definitionDimensionsTempStagingTableName);
            handle.commit();
            handle.begin();
            context.stop();
            long endTime = System.currentTimeMillis();
            logger.debug("Flushing staging tables took " + (endTime - startTime) / 1000 + " seconds");
        } catch (Exception e) {
            logger.error("Failed to write measurements, definitions, or dimensions to database", e);
            if (handle.isInTransaction()) {
                handle.rollback();
            }
            handle.begin();
        }

    }

    private void commitBatch() {
        long startTime = System.currentTimeMillis();
        Timer.Context context = commitTimer.time();
        metricsBatch.execute();
        stagedDefinitionsBatch.execute();
        stagedDimensionsBatch.execute();
        stageddefinitionDimensionsBatch.execute();
        handle.commit();
        updateIdCaches();
        handle.begin();
        context.stop();
        long endTime = System.currentTimeMillis();
        logger.debug("Committing batch took " + (endTime - startTime) / 1000 + " seconds");
    }

    private void updateIdCaches() {
        for (Sha1HashId defId : definitionIdSet) {
            definitionsIdCache.put(defId, defId);
        }
        definitionIdSet.clear();

        for (Sha1HashId dimId : dimensionIdSet) {
            dimensionsIdCache.put(dimId, dimId);
        }
        dimensionIdSet.clear();

        for (Sha1HashId defDimsId : definitionDimensionsIdSet) {
            definitionDimensionsIdCache.put(defDimsId, defDimsId);
        }
        definitionDimensionsIdSet.clear();

    }
}
