package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.RouteCreateRequest;
import com.evops.coldchain.dto.RouteDraftRequest;
import com.evops.coldchain.dto.SegmentDraftRequest;
import com.evops.coldchain.entity.Route;
import com.evops.coldchain.entity.RouteSegment;
import com.evops.coldchain.entity.RouteSegmentSnap;
import com.evops.coldchain.entity.RouteVersion;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.RouteMapper;
import com.evops.coldchain.mapper.RouteSegmentMapper;
import com.evops.coldchain.mapper.RouteSegmentSnapMapper;
import com.evops.coldchain.mapper.RouteVersionMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路线温区分段服务。
 *
 * 版本与分段规则：
 * - 一条路线按季节（SPRING/SUMMER/AUTUMN/WINTER，GENERAL 为通用兜底）各维护一套版本序列，
 *   版本行只追加；DRAFT 草稿可反复编排（仅草稿分段允许重排），发布后分段行永不修改。
 * - 分段按里程 [fromKm,toKm) 从 0 连续覆盖到路线 totalKm，相邻段必须首尾相接：
 *   不允许空档，也不允许重叠；每段指定允许温区、越限缓冲时长（分钟）与责任人。
 * - 发布是“热替换”：即时生效的新版本会把同季节当前在效版本置 SUPERSEDED；
 *   新版本只影响尚未发运（未固化）的批次；批次发运瞬间固化分段快照，此后与路线版本脱钩。
 * - 撤回只能撤销“尚未生效”的版本（DRAFT，或已发布但生效时间窗未到、且从未被批次引用）；
 *   一旦生效或已被批次固化快照即不可撤回。
 * - 发布前提供与当前在效版本的分段差异（ADDED/REMOVED/MODIFIED/UNCHANGED）。
 */
@Service
public class RouteService {

    @Resource
    private RouteMapper routeMapper;
    @Resource
    private RouteVersionMapper versionMapper;
    @Resource
    private RouteSegmentMapper segmentMapper;
    @Resource
    private RouteSegmentSnapMapper snapMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;

    // ---------------- 路线建档 ----------------

    @Transactional
    public Route createRoute(RouteCreateRequest req) {
        if (req.getTotalKm().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("路线总里程必须大于0");
        }
        Route route = new Route();
        route.setRouteNo("RL" + sequenceMapper.nextRouteNo());
        route.setRouteName(req.getRouteName());
        route.setOrigin(req.getOrigin());
        route.setDestination(req.getDestination());
        route.setTotalKm(req.getTotalKm());
        route.setStatus(CcConstants.ACTIVE);
        route.setRemark(req.getRemark());
        routeMapper.insert(route);

        opLogService.log(CcConstants.ENT_ROUTE, route.getId(), null, CcConstants.ACT_CREATE,
                String.format("路线建档：%s(%s)，%s -> %s，总里程=%skm",
                        route.getRouteName(), route.getRouteNo(),
                        nz(route.getOrigin()), nz(route.getDestination()), route.getTotalKm()));
        return route;
    }

    // ---------------- 草稿编排 ----------------

    /**
     * 新建或覆盖某路线某季节的草稿版本（同一路线同一季节至多一个 DRAFT）。
     * 仅 DRAFT 的分段允许重排；已发布版本分段不可改，只能再出新版本。
     */
    @Transactional
    public RouteVersion saveDraft(Long routeId, RouteDraftRequest req) {
        Route route = requireActiveRoute(routeId);
        String season = SeasonResolver.normalize(req.getSeason());
        List<SegmentDraftRequest> segments = req.getSegments();
        if (segments == null || segments.isEmpty()) {
            throw new BusinessException("至少配置一个温区分段");
        }
        validateWindow(req.getEffectiveFrom(), req.getEffectiveTo());
        // 连续性与单段合法性（无空档/重叠、覆盖全程）
        validateSegments(route.getTotalKm(), segments);

        RouteVersion draft = findOpenDraft(routeId, season);
        boolean created = draft == null;
        if (created) {
            draft = new RouteVersion();
            draft.setRouteId(routeId);
            draft.setSeason(season);
            draft.setVersionNo(nextVersionNo(routeId, season));
            draft.setStatus(CcConstants.ROUTE_DRAFT);
        }
        draft.setEffectiveFrom(req.getEffectiveFrom());
        draft.setEffectiveTo(req.getEffectiveTo());
        draft.setChangeReason(req.getChangeReason());
        if (created) {
            versionMapper.insert(draft);
        } else {
            versionMapper.updateById(draft);
            // 清掉旧草稿分段（草稿从未生效、无快照引用，允许重排）
            segmentMapper.delete(new QueryWrapper<RouteSegment>().eq("version_id", draft.getId()));
        }

        List<SegmentDraftRequest> ordered = sorted(segments);
        int seq = 1;
        for (SegmentDraftRequest s : ordered) {
            RouteSegment seg = new RouteSegment();
            seg.setVersionId(draft.getId());
            seg.setSegmentSeq(seq++);
            seg.setFromKm(s.getFromKm());
            seg.setToKm(s.getToKm());
            seg.setTempMin(s.getTempMin());
            seg.setTempMax(s.getTempMax());
            seg.setBufferMinutes(s.getBufferMinutes());
            seg.setOwnerName(s.getOwnerName());
            seg.setOwnerPhone(s.getOwnerPhone());
            seg.setRemark(s.getRemark());
            segmentMapper.insert(seg);
        }

        opLogService.log(CcConstants.ENT_ROUTE, routeId, null, CcConstants.ACT_ROUTE_DRAFT,
                String.format("路线 %s %s季 V%d 草稿编排：%d 个温区分段（0~%skm，相邻无空档/重叠），拟生效=%s",
                        route.getRouteNo(), season, draft.getVersionNo(), ordered.size(),
                        route.getTotalKm(), req.getEffectiveFrom() == null ? "发布即生效" : req.getEffectiveFrom()));
        return draft;
    }

    // ---------------- 发布前差异 ----------------

    /**
     * 草稿相对“同季节当前在效版本”的分段差异。
     * 按段序号对齐：ADDED 新增 / REMOVED 删除 / MODIFIED 关键属性变化 / UNCHANGED 不变。
     */
    public Map<String, Object> previewDiff(Long versionId) {
        RouteVersion draft = requireVersion(versionId);
        if (!CcConstants.ROUTE_DRAFT.equals(draft.getStatus())) {
            throw new BusinessException("只有草稿版本可以查看发布差异，当前状态: " + draft.getStatus());
        }
        Route route = requireRoute(draft.getRouteId());
        RouteVersion current = findInForceVersion(draft.getRouteId(), draft.getSeason(), LocalDateTime.now());

        List<RouteSegment> draftSegs = listSegments(versionId);
        List<RouteSegment> currentSegs = current == null
                ? new ArrayList<>() : listSegments(current.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("routeId", route.getId());
        result.put("routeName", route.getRouteName());
        result.put("season", draft.getSeason());
        result.put("draftVersionNo", draft.getVersionNo());
        result.put("currentVersionNo", current == null ? null : current.getVersionNo());
        result.put("changes", buildSegmentChanges(currentSegs, draftSegs));
        return result;
    }

    // ---------------- 发布（热替换） ----------------

    /**
     * 发布草稿：
     * - 即时生效（effectiveFrom <= 当前时间）时，把同季节当前在效版本置 SUPERSEDED；
     * - 预约生效（未来时间窗）时，不得与其它生效版本的时间窗重叠；
     * - 发布后只影响尚未发运的批次；已经发运的批次引用发运时固化的原快照。
     */
    @Transactional
    public Map<String, Object> publish(Long versionId) {
        RouteVersion draft = requireVersion(versionId);
        if (!CcConstants.ROUTE_DRAFT.equals(draft.getStatus())) {
            throw new BusinessException("仅草稿版本允许发布，当前状态: " + draft.getStatus());
        }
        Route route = requireActiveRoute(draft.getRouteId());

        List<RouteSegment> segments = listSegments(versionId);
        if (segments.isEmpty()) {
            throw new BusinessException("草稿没有任何温区分段，不能发布");
        }
        // 发布前再校验一次连续性，防止路线总里程变更后草稿越界
        validateCoverage(route.getTotalKm(), segments);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = draft.getEffectiveFrom() == null ? now : draft.getEffectiveFrom();
        LocalDateTime to = draft.getEffectiveTo();
        validateWindow(from, to);

        boolean immediate = !from.isAfter(now);
        List<RouteVersion> supersede = new ArrayList<>();

        List<RouteVersion> effective = listEffective(draft.getRouteId(), draft.getSeason());
        for (RouteVersion v : effective) {
            if (v.getId().equals(draft.getId())) {
                continue;
            }
            boolean overlap = windowsOverlap(from, to, v.getEffectiveFrom(), v.getEffectiveTo());
            if (!overlap) {
                continue;
            }
            boolean inForceNow = v.getEffectiveFrom() != null && !v.getEffectiveFrom().isAfter(now);
            if (immediate && inForceNow) {
                // 同季节当前在效版本：热替换
                supersede.add(v);
            } else {
                // 预约版本与未来/在效窗口冲突，不允许静默覆盖
                throw new BusinessException(String.format(
                        "生效时间窗与 %s季 V%d（%s ~ %s）重叠，请调整生效时间或先撤回该版本",
                        draft.getSeason(), v.getVersionNo(),
                        v.getEffectiveFrom(), v.getEffectiveTo() == null ? "长期" : v.getEffectiveTo()));
            }
        }

        for (RouteVersion old : supersede) {
            old.setStatus(CcConstants.SUPERSEDED);
            versionMapper.updateById(old);
        }

        draft.setStatus(CcConstants.EFFECTIVE);
        draft.setEffectiveFrom(from);
        draft.setPublishTime(now);
        versionMapper.updateById(draft);

        List<Map<String, Object>> changes = buildSegmentChanges(
                supersede.isEmpty() ? new ArrayList<>() : listSegments(supersede.get(0).getId()), segments);

        opLogService.log(CcConstants.ENT_ROUTE, route.getId(), null, CcConstants.ACT_ROUTE_PUBLISH,
                String.format("路线 %s %s季 V%d 发布（%s）：%s，热替换 V%s；分段差异 %s",
                        route.getRouteNo(), draft.getSeason(), draft.getVersionNo(),
                        immediate ? "即时生效" : "预约 " + from + " 生效",
                        to == null ? "长期有效" : "至 " + to,
                        supersede.isEmpty() ? "无（首次发布）" : String.valueOf(supersede.get(0).getVersionNo()),
                        summarizeChanges(changes)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", draft);
        result.put("supersededVersionNos", supersede.stream().map(RouteVersion::getVersionNo).toArray());
        result.put("changes", changes);
        result.put("segments", segments);
        return result;
    }

    // ---------------- 撤回（仅未生效） ----------------

    /**
     * 撤回版本：只能撤销尚未生效的版本——
     * DRAFT，或已发布但生效时间窗未到、且从未被任何运输批次固化快照的版本。
     * 已在效或已被批次引用的版本拒绝撤回（批次必须继续按原快照执行）。
     */
    @Transactional
    public RouteVersion withdraw(Long versionId, String reason) {
        RouteVersion version = requireVersion(versionId);
        Route route = requireRoute(version.getRouteId());
        LocalDateTime now = LocalDateTime.now();

        if (CcConstants.SUPERSEDED.equals(version.getStatus()) || CcConstants.REVOKED.equals(version.getStatus())) {
            throw new BusinessException("版本已"
                    + (CcConstants.REVOKED.equals(version.getStatus()) ? "撤回" : "被新版本替换")
                    + "，不能撤回");
        }
        if (CcConstants.EFFECTIVE.equals(version.getStatus())) {
            boolean inForce = version.getEffectiveFrom() != null && !version.getEffectiveFrom().isAfter(now);
            if (inForce) {
                throw new BusinessException(String.format(
                        "路线 %s %s季 V%d 已生效（生效时间 %s），不能撤回；请发布新版本进行调整",
                        route.getRouteNo(), version.getSeason(), version.getVersionNo(), version.getEffectiveFrom()));
            }
        }
        if (isReferencedByBatch(versionId)) {
            throw new BusinessException(String.format(
                    "路线 %s %s季 V%d 已被运输批次引用并固化分段快照，不能撤回（在途批次继续引用原快照）",
                    route.getRouteNo(), version.getSeason(), version.getVersionNo()));
        }

        version.setStatus(CcConstants.REVOKED);
        versionMapper.updateById(version);

        opLogService.log(CcConstants.ENT_ROUTE, route.getId(), null, CcConstants.ACT_ROUTE_WITHDRAW,
                String.format("路线 %s %s季 V%d 撤回（尚未生效，未被批次引用）。原因：%s",
                        route.getRouteNo(), version.getSeason(), version.getVersionNo(), nz(reason)));
        return version;
    }

    // ---------------- 发运选版与快照 ----------------

    /**
     * 发运（批次开始执行）时点选取生效版本：先按运输时点所属季节取“时间窗覆盖该时点”的在效版本，
     * 当季未配置时回退到 GENERAL 通用版本；都没有则拒绝发运。
     *
     * 注意：季节版本在“发运”而非派车时绑定——派车后、发运前发布的新版本仍会作用于该未发运批次；
     * 一旦发运，逐段快照固化，此后版本热替换/撤回与本批次脱钩。
     */
    public RouteVersion selectEffectiveAt(Long routeId, LocalDateTime at) {
        requireActiveRoute(routeId);
        String season = SeasonResolver.seasonOf(at);
        RouteVersion version = findInForceVersion(routeId, season, at);
        if (version == null && !CcConstants.SEASON_GENERAL.equals(season)) {
            version = findInForceVersion(routeId, CcConstants.SEASON_GENERAL, at);
        }
        if (version == null) {
            throw new BusinessException(String.format(
                    "路线在 %s（%s）没有生效的温区分段版本，请先完成季节配置并发布", at, season));
        }
        return version;
    }

    /** 把选定版本的全部分段逐段复制为批次快照（此后与路线版本脱钩） */
    @Transactional
    public List<RouteSegmentSnap> snapshotForBatch(Long batchId, Long versionId) {
        List<RouteSegment> segments = listSegments(versionId);
        List<RouteSegmentSnap> snaps = new ArrayList<>();
        for (RouteSegment s : segments) {
            RouteSegmentSnap snap = new RouteSegmentSnap();
            snap.setBatchId(batchId);
            snap.setSegmentSeq(s.getSegmentSeq());
            snap.setFromKm(s.getFromKm());
            snap.setToKm(s.getToKm());
            snap.setTempMin(s.getTempMin());
            snap.setTempMax(s.getTempMax());
            snap.setBufferMinutes(s.getBufferMinutes());
            snap.setOwnerName(s.getOwnerName());
            snap.setOwnerPhone(s.getOwnerPhone());
            snap.setRemark(s.getRemark());
            snapMapper.insert(snap);
            snaps.add(snap);
        }
        return snaps;
    }

    public List<RouteSegmentSnap> listSnapshotsByBatch(Long batchId) {
        return snapMapper.selectList(new QueryWrapper<RouteSegmentSnap>()
                .eq("batch_id", batchId)
                .orderByAsc("segment_seq"));
    }

    // ---------------- 查询与校验 ----------------

    public Route requireRoute(Long routeId) {
        Route route = routeMapper.selectById(routeId);
        if (route == null) {
            throw new BusinessException("运输路线不存在: " + routeId);
        }
        return route;
    }

    public Route requireActiveRoute(Long routeId) {
        Route route = requireRoute(routeId);
        if (CcConstants.REVOKED.equals(route.getStatus())) {
            throw new BusinessException("路线已停用: " + route.getRouteNo());
        }
        return route;
    }

    public RouteVersion requireVersion(Long versionId) {
        RouteVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("路线版本不存在: " + versionId);
        }
        return version;
    }

    public List<RouteSegment> listSegments(Long versionId) {
        return segmentMapper.selectList(new QueryWrapper<RouteSegment>()
                .eq("version_id", versionId)
                .orderByAsc("segment_seq"));
    }

    public List<RouteVersion> listVersions(Long routeId) {
        return versionMapper.selectList(new QueryWrapper<RouteVersion>()
                .eq("route_id", routeId)
                .orderByAsc("season")
                .orderByDesc("version_no"));
    }

    public List<Route> listRoutes() {
        return routeMapper.selectList(new QueryWrapper<Route>().orderByDesc("id"));
    }

    private RouteVersion findOpenDraft(Long routeId, String season) {
        return versionMapper.selectOne(new QueryWrapper<RouteVersion>()
                .eq("route_id", routeId)
                .eq("season", season)
                .eq("status", CcConstants.ROUTE_DRAFT)
                .last("LIMIT 1"));
    }

    private Integer nextVersionNo(Long routeId, String season) {
        RouteVersion latest = versionMapper.selectOne(new QueryWrapper<RouteVersion>()
                .eq("route_id", routeId)
                .eq("season", season)
                .orderByDesc("version_no")
                .last("LIMIT 1"));
        return latest == null ? 1 : latest.getVersionNo() + 1;
    }

    private List<RouteVersion> listEffective(Long routeId, String season) {
        return versionMapper.selectList(new QueryWrapper<RouteVersion>()
                .eq("route_id", routeId)
                .eq("season", season)
                .eq("status", CcConstants.EFFECTIVE)
                .orderByDesc("version_no"));
    }

    /** 取某季节在指定时点处于生效时间窗内的最新版本；时间窗不覆盖返回 null */
    private RouteVersion findInForceVersion(Long routeId, String season, LocalDateTime at) {
        List<RouteVersion> versions = listEffective(routeId, season);
        for (RouteVersion v : versions) {
            LocalDateTime from = v.getEffectiveFrom();
            LocalDateTime to = v.getEffectiveTo();
            boolean afterStart = from == null || !from.isAfter(at);
            boolean beforeEnd = to == null || to.isAfter(at);
            if (afterStart && beforeEnd) {
                return v; // 已按 version_no 倒序，命中即最新
            }
        }
        return null;
    }

    private boolean isReferencedByBatch(Long versionId) {
        Long count = batchMapper.selectCount(new QueryWrapper<TransportBatch>()
                .eq("route_version_id", versionId));
        return count != null && count > 0;
    }

    /** 时间窗合法性：起点不空、终点晚于起点 */
    private void validateWindow(LocalDateTime from, LocalDateTime to) {
        if (to != null) {
            LocalDateTime start = from == null ? LocalDateTime.now() : from;
            if (!to.isAfter(start)) {
                throw new BusinessException("生效终点必须晚于生效起点");
            }
        }
    }

    /** [from,to) 时间窗是否重叠（端点相接不算重叠；null 终点表示长期） */
    private boolean windowsOverlap(LocalDateTime f1, LocalDateTime t1,
                                   LocalDateTime f2, LocalDateTime t2) {
        LocalDateTime a0 = f1 == null ? LocalDateTime.MIN : f1;
        LocalDateTime b0 = f2 == null ? LocalDateTime.MIN : f2;
        boolean leftBeforeRight = t1 == null || t1.isAfter(b0);
        boolean rightBeforeLeft = t2 == null || t2.isAfter(a0);
        return leftBeforeRight && rightBeforeLeft;
    }

    private void validateSegments(BigDecimal totalKm, List<SegmentDraftRequest> segments) {
        List<SegmentDraftRequest> ordered = sorted(segments);
        BigDecimal cursor = BigDecimal.ZERO;
        for (int i = 0; i < ordered.size(); i++) {
            SegmentDraftRequest s = ordered.get(i);
            if (s.getTempMin().compareTo(s.getTempMax()) > 0) {
                throw new BusinessException(String.format("第%d段允许温度下限不能高于上限", i + 1));
            }
            if (s.getBufferMinutes() == null || s.getBufferMinutes() < 0) {
                throw new BusinessException(String.format("第%d段缓冲时长不能为负", i + 1));
            }
            if (s.getOwnerName() == null || s.getOwnerName().trim().isEmpty()) {
                throw new BusinessException(String.format("第%d段责任人不能为空", i + 1));
            }
            if (s.getFromKm().compareTo(s.getToKm()) >= 0) {
                throw new BusinessException(String.format(
                        "第%d段里程非法：起点 %s 必须小于终点 %s", i + 1, s.getFromKm(), s.getToKm()));
            }
            // 相邻段必须首尾相接：本段起点必须正好等于上一段终点（首段必须从 0 开始）
            if (s.getFromKm().compareTo(cursor) != 0) {
                throw new BusinessException(String.format(
                        "温区分段在 %skm 处%s：第%d段应从 %skm 开始（相邻分段不能有空档或重叠）",
                        cursor, s.getFromKm().compareTo(cursor) > 0 ? "存在空档" : "存在重叠",
                        i + 1, cursor));
            }
            if (s.getToKm().compareTo(totalKm) > 0) {
                throw new BusinessException(String.format(
                        "第%d段终点 %skm 超出路线总里程 %skm", i + 1, s.getToKm(), totalKm));
            }
            cursor = s.getToKm();
        }
        if (cursor.compareTo(totalKm) != 0) {
            throw new BusinessException(String.format(
                    "温区分段未覆盖全程：末段止于 %skm，路线总里程为 %skm（不能留有空档）", cursor, totalKm));
        }
    }

    /** 发布前对已落库分段复核连续性（防路线总里程被调整） */
    private void validateCoverage(BigDecimal totalKm, List<RouteSegment> segments) {
        List<SegmentDraftRequest> asDraft = new ArrayList<>();
        for (RouteSegment s : segments) {
            SegmentDraftRequest d = new SegmentDraftRequest();
            d.setFromKm(s.getFromKm());
            d.setToKm(s.getToKm());
            d.setTempMin(s.getTempMin());
            d.setTempMax(s.getTempMax());
            d.setBufferMinutes(s.getBufferMinutes());
            d.setOwnerName(s.getOwnerName());
            d.setOwnerPhone(s.getOwnerPhone());
            d.setRemark(s.getRemark());
            asDraft.add(d);
        }
        validateSegments(totalKm, asDraft);
    }

    private List<SegmentDraftRequest> sorted(List<SegmentDraftRequest> segments) {
        List<SegmentDraftRequest> ordered = new ArrayList<>(segments);
        ordered.sort(Comparator.comparing(SegmentDraftRequest::getFromKm));
        return ordered;
    }

    /** 按段序号对齐生成差异；关键业务属性任一不同即 MODIFIED */
    private List<Map<String, Object>> buildSegmentChanges(List<RouteSegment> oldSegs, List<RouteSegment> newSegs) {
        Map<Integer, RouteSegment> oldMap = toSeqMap(oldSegs);
        Map<Integer, RouteSegment> newMap = toSeqMap(newSegs);
        int max = 0;
        for (Integer k : oldMap.keySet()) {
            max = Math.max(max, k);
        }
        for (Integer k : newMap.keySet()) {
            max = Math.max(max, k);
        }
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int seq = 1; seq <= max; seq++) {
            RouteSegment o = oldMap.get(seq);
            RouteSegment n = newMap.get(seq);
            String type;
            if (o == null && n != null) {
                type = "ADDED";
            } else if (o != null && n == null) {
                type = "REMOVED";
            } else if (sameBusinessValues(o, n)) {
                type = "UNCHANGED";
            } else {
                type = "MODIFIED";
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("segmentSeq", seq);
            row.put("changeType", type);
            row.put("before", o);
            row.put("after", n);
            changes.add(row);
        }
        return changes;
    }

    private Map<Integer, RouteSegment> toSeqMap(List<RouteSegment> segs) {
        Map<Integer, RouteSegment> map = new LinkedHashMap<>();
        for (RouteSegment s : segs) {
            map.put(s.getSegmentSeq(), s);
        }
        return map;
    }

    private boolean sameBusinessValues(RouteSegment o, RouteSegment n) {
        return eq(o.getFromKm(), n.getFromKm())
                && eq(o.getToKm(), n.getToKm())
                && eq(o.getTempMin(), n.getTempMin())
                && eq(o.getTempMax(), n.getTempMax())
                && o.getBufferMinutes().equals(n.getBufferMinutes())
                && o.getOwnerName().equals(n.getOwnerName())
                && nz(o.getOwnerPhone()).equals(nz(n.getOwnerPhone()));
    }

    private boolean eq(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private String summarizeChanges(List<Map<String, Object>> changes) {
        int added = 0, removed = 0, modified = 0;
        for (Map<String, Object> c : changes) {
            switch ((String) c.get("changeType")) {
                case "ADDED": added++; break;
                case "REMOVED": removed++; break;
                case "MODIFIED": modified++; break;
                default:
            }
        }
        return String.format("新增%d/删除%d/调整%d段，其余不变", added, removed, modified);
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
