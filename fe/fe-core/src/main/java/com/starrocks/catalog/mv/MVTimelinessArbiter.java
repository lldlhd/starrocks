// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.catalog.mv;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.starrocks.catalog.BaseTableInfo;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.MvBaseTableUpdateInfo;
import com.starrocks.catalog.MvUpdateInfo;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.TableProperty;
import com.starrocks.common.AnalysisException;
import com.starrocks.sql.common.PCell;
import com.starrocks.sql.optimizer.rule.transformation.materialization.MvUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.starrocks.catalog.MvRefreshArbiter.getMvBaseTableUpdateInfo;
import static com.starrocks.catalog.MvRefreshArbiter.needsToRefreshTable;

/**
 * {@link MVTimelinessArbiter} is the base class of all materialized view timeliness arbiters which is used to determine the mv's
 * timeliness. The timeliness is important for a mv which is used to mv's rewrite and refresh. When a partition of the mv has
 * been refreshed, it can be used for query rewrite and is not needed to be refreshed again; otherwise the partition cannot be
 * used for query rewrite and needs to be refreshed again.
 */
public abstract class MVTimelinessArbiter {
    private static final Logger LOG = LogManager.getLogger(MVTimelinessArbiter.class);

    // the materialized view to check
    protected final MaterializedView mv;
    // whether is query rewrite or mv refresh
    protected final boolean isQueryRewrite;

    public MVTimelinessArbiter(MaterializedView mv, boolean isQueryRewrite) {
        this.mv = mv;
        this.isQueryRewrite = isQueryRewrite;
    }

    /**
     * Materialized Views' base tables have two kinds: ref base table and non-ref base table.
     * - If non ref base tables updated, need refresh all mv partitions.
     * - If ref base table updated, need refresh the ref base table's updated partitions.
     * </p>
     * eg:
     * CREATE MATERIALIZED VIEW mv1
     * PARTITION BY k1
     * DISTRIBUTED BY HASH(k1) BUCKETS 10
     * AS
     * SELECT k1, v1 as k2, v2 as k3
     * from t1 join t2
     * on t1.k1 and t2.kk1;
     * </p>
     * - t1 is mv1's ref base table because mv1's partition column k1 is deduced from t1
     * - t2 is mv1's non-ref base table because mv1's partition column k1 is not associated with t2.
     * @return : partitioned materialized view's all need updated partition names.
     */
    public MvUpdateInfo getMVTimelinessUpdateInfo(TableProperty.QueryRewriteConsistencyMode mode) throws AnalysisException {
        if (mode == TableProperty.QueryRewriteConsistencyMode.LOOSE) {
            return getMVTimelinessUpdateInfoInLoose();
        } else {
            return getMVTimelinessUpdateInfoInChecked();
        }
    }

    /**
     * In checked mode, need to check mv partition's data is consistent with base table's partition's data.
     * @return mv's update info in checked mode
     */
    protected abstract MvUpdateInfo getMVTimelinessUpdateInfoInChecked() throws AnalysisException;

    /**
     * In Loose mode, do not need to check mv partition's data is consistent with base table's partition's data.
     * Only need to check the mv partition existence.
     */
    protected abstract MvUpdateInfo getMVTimelinessUpdateInfoInLoose();

    /**
     * Determine the refresh type of the materialized view.
     * @param refBaseTablePartitionCols ref base table partition infos
     * @return the refresh type of the materialized view
     */
    protected boolean needsRefreshOnNonRefBaseTables(Map<Table, List<Column>> refBaseTablePartitionCols) {
        TableProperty tableProperty = mv.getTableProperty();
        boolean isDisableExternalForceQueryRewrite = tableProperty != null &&
                tableProperty.getForceExternalTableQueryRewrite() == TableProperty.QueryRewriteConsistencyMode.DISABLE;
        for (BaseTableInfo tableInfo : mv.getBaseTableInfos()) {
            Table baseTable = MvUtils.getTableChecked(tableInfo);
            // skip view
            if (baseTable.isView()) {
                continue;
            }
            if (refBaseTablePartitionCols.containsKey(baseTable)) {
                continue;
            }
            // skip external table not supported for query rewrite, return all partitions ?
            // skip check external table if the external does not support rewrite.
            if (!baseTable.isNativeTableOrMaterializedView() && isDisableExternalForceQueryRewrite) {
                return true;
            }
            // If the non-ref table has already changed, need refresh all materialized views' partitions.
            if (needsToRefreshTable(mv, baseTable, isQueryRewrite)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Update mv partition names that need to refresh from mvRefreshInfo and collected baseToMvNameRef.
     * @param baseChangedPartitionNames base table changed partition names
     * @param baseToMvNameRef base table to mv name reference
     */
    protected Set<String> getMVToRefreshPartitionNames(
            Map<Table, Set<String>> baseChangedPartitionNames,
            Map<Table, Map<String, Set<String>>> baseToMvNameRef) throws AnalysisException {
        Set<String> needRefreshMvPartitionNames = Sets.newHashSet();
        if (baseToMvNameRef.isEmpty()) {
            return needRefreshMvPartitionNames;
        }
        for (Map.Entry<Table, Set<String>> entry : baseChangedPartitionNames.entrySet()) {
            Table baseTable = entry.getKey();
            if (!baseToMvNameRef.containsKey(baseTable)) {
                throw new AnalysisException(String.format("Can't find base table %s from baseToMvNameRef",
                        baseTable.getName()));
            }
            Map<String, Set<String>> baseTableRefMvPartNames = baseToMvNameRef.get(baseTable);
            for (String partitionName : entry.getValue()) {
                if (!baseTableRefMvPartNames.containsKey(partitionName)) {
                    throw new AnalysisException(String.format("Can't find base table %s from baseToMvNameRef",
                            baseTable.getName()));
                }
                needRefreshMvPartitionNames.addAll(baseTableRefMvPartNames.get(partitionName));
            }
        }
        return needRefreshMvPartitionNames;
    }

    /**
     * Collect ref base table's update partition infos
     * @param refBaseTableAndColumns ref base table and columns of mv
     * @return ref base table's changed partition names
     */
    protected Map<Table, Set<String>> collectBaseTableUpdatePartitionNames(Map<Table, List<Column>> refBaseTableAndColumns,
                                                                           MvUpdateInfo mvUpdateInfo) {
        Map<Table, Set<String>> baseChangedPartitionNames = Maps.newHashMap();
        for (Table baseTable : refBaseTableAndColumns.keySet()) {
            MvBaseTableUpdateInfo mvBaseTableUpdateInfo = getMvBaseTableUpdateInfo(mv, baseTable,
                    true, isQueryRewrite);
            mvUpdateInfo.getBaseTableUpdateInfos().put(baseTable, mvBaseTableUpdateInfo);
            // If base table is a mv, its to-update partitions may not be created yet, skip it
            baseChangedPartitionNames.put(baseTable, mvBaseTableUpdateInfo.getToRefreshPartitionNames());
        }
        return baseChangedPartitionNames;
    }

    protected void collectBaseTableUpdatePartitionNamesInLoose(MvUpdateInfo mvUpdateInfo) {
        Map<Table, List<Column>> refBaseTableAndColumns = mv.getRefBaseTablePartitionColumns();
        // collect & update mv's to refresh partitions based on base table's partition changes
        collectBaseTableUpdatePartitionNames(refBaseTableAndColumns, mvUpdateInfo);
        Set<Table> refBaseTables = mv.getRefBaseTablePartitionColumns().keySet();
        MaterializedView.AsyncRefreshContext context = mv.getRefreshScheme().getAsyncRefreshContext();
        for (Table table : refBaseTables) {
            Map<String, MaterializedView.BasePartitionInfo> mvBaseTableVisibleVersionMap =
                    context.getBaseTableVisibleVersionMap()
                            .computeIfAbsent(table.getId(), k -> Maps.newHashMap());
            for (String partitionName : mvBaseTableVisibleVersionMap.keySet()) {
                if (mvUpdateInfo.getBaseTableToRefreshPartitionNames(table) != null) {
                    // in loose mode, ignore partition that both exists in baseTable and mv
                    mvUpdateInfo.getBaseTableToRefreshPartitionNames(table).remove(partitionName);
                }
            }
        }
    }

    /**
     * If base table is materialized view, add partition name to cell mapping into base table partition mapping;
     * otherwise base table(mv) may lose partition names of the real base table changed partitions.
     * @param baseTableUpdateInfoMap base table update info from MvTimelinessInfo
     * @return the base table to its changed partition and cell map if it's mv, empty else
     */
    protected void collectExtraBaseTableChangedPartitions(
            Map<Table, MvBaseTableUpdateInfo> baseTableUpdateInfoMap,
            Consumer<Map.Entry<Table, Map<String, PCell>>> consumer) {
        Map<Table, Map<String, PCell>> extraChangedPartitions = baseTableUpdateInfoMap.entrySet().stream()
                .filter(e -> !e.getValue().getMvPartitionNameToCellMap().isEmpty())
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().getMvPartitionNameToCellMap()));
        for (Map.Entry<Table, Map<String, PCell>> entry : extraChangedPartitions.entrySet()) {
            consumer.accept(entry);
        }
    }

    protected void addEmptyPartitionsToRefresh(MvUpdateInfo mvUpdateInfo) {
        Set<Table> refBaseTables = mv.getRefBaseTablePartitionColumns().keySet();
        boolean allOlapTables = refBaseTables.stream().allMatch(t -> t instanceof OlapTable);
        if (!allOlapTables) {
            return;
        }
        mv.getRangePartitionMap().keySet().forEach(mvPartitionName -> {
            if (!mv.getPartition(mvPartitionName).getDefaultPhysicalPartition().hasStorageData()) {
                // add empty partitions
                mvUpdateInfo.addMvToRefreshPartitionNames(mvPartitionName);
            }
        });
    }
}