# EVOPS 基础工作区

这是 springboot 的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

根目录的 `..\..\docs\schema\evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

## 冷链药品运输台账（V1）

在骨架上新增 `com.evops.coldchain` 模块，跑通建档 → 派车 → 发运 → 签收 → 异常标记；
结算与监管报送留待后续版本。

### 核心规则

- **药品档案版本化**：建档生成 V1；变更只追加新版本行，旧版本批准文号/温区/包装原值永不修改。
- **批次按原版本核对**：发货单创建即锁定档案版本，派车时把该版本（批准文号、储存温区上下限、
  包装规格、版本号）快照冗余到运输批次。档案之后即使先变更，在途批次核对、签收一律以快照为准。
- **稳定批次号**：由 H2 序列生成（`PC年+6位序列`），数据库层唯一、单调、重启不复用。
- **途中温度按时间留痕**：以批次快照温区判定是否越限，越限自动追加温度异常事件。
- **异常只追加**：异常事件、凭证（附件/照片/说明）全部 INSERT-only，不覆盖、不更新、不删除；
  处理闭环只追加处理说明，不改原描述与凭证。
- **签收才闭环**：签收需按快照核对实物批准文号、包装规格、数量，且无未处理（OPEN）异常。
- **撤回不删数据**：仅置 `REVOKED` 作废标记；批次、温度、异常、凭证、日志原始行全部保留。
- **关键操作留痕**：建档/变更/派车/发运/温度上报/异常标记/凭证追加/异常处理/签收/撤回
  统一写入 `t_cc_op_log`，记录操作者账号、操作者 ID 与时间（MyBatis-Plus 自动填充）。

### 表结构（详见 schema.sql）

`t_cc_drug` / `t_cc_drug_version`（档案+版本）、`t_cc_vehicle`、`t_cc_shipment_order`、
`t_cc_transport_batch`（含档案快照）、`t_cc_temperature_log`、`t_cc_exception`、
`t_cc_exception_evidence`、`t_cc_op_log`，以及 `seq_cc_drug_no/seq_cc_order_no/seq_cc_batch_no` 序列。
V2 另增路线温区分段四张表（见下节）与 `seq_cc_route_no` 序列。

### 接口（均在 `/api/cold-chain` 下，HTTP Basic 认证）

内置账号：`dispatcher/dispatcher123`（调度）、`carrier/carrier123`（在途）、
`receiver/receiver123`（签收）、`admin/admin123`（冷链温控管理员，V5 预警查看/导出/模板维护）、
`qa/qa123`（V6 质量复核岗，唯一可复核关闭分级异常的角色）、
`inspector/inspector123`（V7 监管岗，抽检办理/导出/运输审批，授权 EAST、NORTH 区域）、
`inspector2/inspector123`（V7 第二个监管账号，仅授权 SOUTH 区域）、`bootstrap/bootstrap`。

| 动作 | 方法与路径 |
| --- | --- |
| 药品建档 | `POST /drugs` |
| 档案变更（追加版本） | `POST /drugs/{id}/versions` |
| 查看档案版本 | `GET /drugs/{id}/versions` |
| 车辆登记 / 列表 | `POST /vehicles` / `GET /vehicles` |
| 创建发货单（锁版本） | `POST /orders` |
| 派车（生成批次+快照） | `POST /batches/dispatch` |
| 发运 | `POST /batches/{id}/ship` |
| 温度上报 | `POST /batches/temperatures` |
| 异常标记 | `POST /batches/exceptions` |
| 追加异常凭证 | `POST /batches/exceptions/evidence` |
| 异常处理闭环 | `POST /batches/exceptions/{id}/resolve` |
| 按快照核对签收 | `POST /batches/sign` |
| 撤回（作废不删） | `POST /batches/revoke` |
| 批次完整台账 | `GET /batches/{id}/ledger` |

示例：

```bash
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/drugs \
  -d '{"drugName":"HPV疫苗","approvalNo":"国药准字S20200011","tempMin":2,"tempMax":8,"packageSpec":"0.5ml/支"}'
```

### 验证

`mvn test` 运行 `ColdChainFlowIntegrationTest`，覆盖全链路及关键不变量：
档案先变更后批次仍按 V1 核对、越限自动异常、凭证追加不覆盖、OPEN 异常拦截签收、
按原版本签收闭环、撤回不删记录、批次号稳定唯一、全链路操作者/时间留痕。

## 冷链运输 V2：路线温区分段

在 V1 台账上增加“按季节维护的路线温区分段”。同一路线可按春/夏/秋/冬（及 GENERAL 通用）
各维护一套版本，每个版本由若干相邻分段组成，每段指定**允许温度、越限缓冲时长（分钟）和责任人**。

### 核心规则

- **分段相邻无空档/无重叠**：分段按里程 `[fromKm, toKm)` 从 0 连续覆盖到路线 `total_km`；
  相邻段必须首尾相接（首段从 0 起、末段到总里程止）。温区倒置、缓冲为负、责任人为空、
  超出总里程一律拒绝，并返回明确停点说明。
- **季节版本只追加**：草稿 `DRAFT` 可反复编排（仅草稿分段允许重排）；发布后置 `EFFECTIVE`，
  同季节当前在效版本被热替换为 `SUPERSEDED`，历史版本与分段行原值永不修改。
- **发布前展示差异**：`GET /routes/versions/{id}/diff` 按段序号给出
  `ADDED/REMOVED/MODIFIED/UNCHANGED`；发布响应也回带差异。
- **新版本只影响未发运批次**：派车只登记走哪条路线；**发运（批次开始执行）瞬间**才按
  “当前季节 + 当前时间”选取生效版本（当季未配置回退 GENERAL），把其全部分段**逐段快照**到
  `t_cc_route_segment_snap`。因此派车后、发运前发布的新版本仍会作用于该批；一旦发运，版本
  热替换/撤回即与本批次脱钩，在途、签收继续引用原快照。
- **按分段温区 + 缓冲时长判定越限**：温度上报需带 `mileageKm`，按里程命中快照分段温区；
  同一段内连续越限未超过该段缓冲时长只留痕、不开异常，超过缓冲才自动开温度异常并定位到该段
  责任人；中途回正常或切换分段会打断连续越限计时。
- **撤回只能撤销尚未生效的版本**：`DRAFT`，或已发布但生效时间窗未到且**从未被批次引用**的
  版本可撤回为 `REVOKED`；已生效或已被批次固化快照的版本拒绝撤回（只能再发新版本调整）。
- 预约生效（未来 `effectiveFrom`）的版本，其时间窗不得与同季节其他生效版本重叠。
- 建档/草稿/发布/撤回与派车均写 `t_cc_op_log`（实体类型 `ROUTE`）。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-2`）

- 新增 `t_cc_route`（路线主档）、`t_cc_route_version`（季节版本）、
  `t_cc_route_segment`（版本分段，只追加）、`t_cc_route_segment_snap`（批次逐段快照）、
  序列 `seq_cc_route_no`。
- `t_cc_transport_batch` 增 `route_id/route_version_id/route_season/route_version_no`；
  `t_cc_temperature_log` 增 `mileage_km/segment_seq`；`t_cc_exception` 增 `segment_seq`。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 |
| --- | --- |
| 路线建档 / 列表 / 详情 | `POST /routes` / `GET /routes` / `GET /routes/{id}` |
| 按季节编排分段草稿 | `POST /routes/{id}/versions/draft` |
| 查看版本分段 | `GET /routes/versions/{versionId}/segments` |
| 发布前分段差异 | `GET /routes/versions/{versionId}/diff` |
| 发布（热替换/预约生效） | `POST /routes/versions/publish` |
| 撤回未生效版本 | `POST /routes/versions/withdraw` |
| 派车携带路线（逐段快照） | `POST /batches/dispatch`（body 增加 `routeId`） |
| 分段温度上报（带里程） | `POST /batches/temperatures`（body 增加 `mileageKm`） |

示例（两段连续覆盖 0~100km 的通用季节版本）：

```bash
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/routes \
  -d '{"routeName":"沪杭冷链线","origin":"上海中心仓","destination":"杭州市一院","totalKm":100.00}'

curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/routes/1/versions/draft \
  -d '{"season":"GENERAL","changeReason":"秋冬季分段",
       "segments":[
         {"fromKm":0,"toKm":50,"tempMin":2,"tempMax":8,"bufferMinutes":30,"ownerName":"王冷链","ownerPhone":"13800000001"},
         {"fromKm":50,"toKm":100,"tempMin":0,"tempMax":5,"bufferMinutes":60,"ownerName":"李干线","ownerPhone":"13800000002"}]}'
```

### V2 验收

`RouteTemperatureSegmentIntegrationTest` 以真实 HTTP 入口（TestRestTemplate + Basic 认证）驱动，
再用 Mapper 直查物理表，确保配置结果**一路追到温区分段与批次快照落库**：
路线建档→季节草稿→发布前差异→发布热替换→派车登记路线→发运逐段快照→按里程分段温区/缓冲判定；
并验证空档/重叠/未覆盖全程/倒置/越界/温区非法/缓冲为负/责任人为空等**失败停点**返回明确说明，
新版本只影响未派车批次、已派车批次持原快照、撤回仅限未生效未被引用版本、季节优先与 GENERAL 回退、
预约时间窗重叠拒绝。

## 冷链运输 V3：车载温度探头校准凭证管理

在 V1/V2 台账上增加车载温度探头的校准凭证全生命周期管理：探头建档登记序列号、量程、
校准机构与有效期；发运前车辆必须绑定仍在有效期内的持证探头；替换探头保留前后关系，
历史温度记录不回写；证书上传生成 SHA-256 摘要，下载与作废逐次写审计流水。

### 核心规则

- **探头建档**：序列号库内唯一；登记量程上下限、校准机构、当前校准有效期；可选随附首份证书。
- **凭证只追加**：新一轮校准=上传新的 VALID 凭证并切换为当前凭证；作废只追加 `VOID` 标记与
  原因/时间，不删除文件、不改原行；作废凭证仍可下载追溯。上传时对文件内容计算 **SHA-256**
  摘要（64 位十六进制，同内容同摘要）。
- **发运前强制绑定有效探头**：发运卡口要求车辆存在当前绑定，且探头在用、凭证 `VALID`、
  未过有效期；未绑定/已过期/已作废一律拦截（接口与页面同一道校验，页面下拉直接禁选）。
  发运瞬间把探头序列号、量程、校准机构、证书编号、有效期、SHA-256 逐字段**快照到批次**，
  此后探头替换、凭证作废都与在途批次脱钩。
- **替换保留前后关系**：车辆-探头绑定只追加；旧绑定置 `released_time` 并写
  `replaced_by_bind_id` 指向新绑定，替换链双向可查。
- **历史温度不回写**：每条温度留痕带 `probe_id`；替换后新测点归新探头，旧温度行原值不动
  （温度表仍为只追加，应用层无更新路径）。
- **关联幂等**：`POST /probes/bind` 必带 `requestId`；同一 requestId 的重复请求直接回第一次的
  绑定结果（`replay=true`），不新增绑定行、不改替换链、不写第二条流水；requestId 换参数拒绝。
- **下载/作废审计**：证书下载记 `last_download_time` 并写 `CERT_DOWNLOAD` 流水（响应头
  `X-Cert-SHA256` 回带摘要），作废写 `CERT_VOID` 流水。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-3`）

- 新增 `t_cc_temp_probe`（探头主档）、`t_cc_probe_cert`（校准凭证，含 sha256/作废/下载记账）、
  `t_cc_vehicle_probe`（车辆绑定，只追加，含替换链与快照）、`t_cc_probe_bind_request`
  （requestId 幂等登记，唯一约束兜底）、序列 `seq_cc_probe_no`（探头编号 `PW+8位`）。
- `t_cc_transport_batch` 增探头凭证快照列（probe_id/bind_id/cert_id/序列号/量程/校准机构/
  证书编号/有效期/摘要）；`t_cc_temperature_log` 增 `probe_id`。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 |
| --- | --- |
| 探头建档（可选随附首份凭证） | `POST /probes` |
| 探头列表 / 详情（含校准历史） | `GET /probes` / `GET /probes/{id}` |
| 上传新校准凭证（生成 SHA-256） | `POST /probes/certs/upload` |
| 下载证书（写审计流水） | `GET /probes/certs/{certId}/download` |
| 作废凭证（不删原文） | `POST /probes/certs/void` |
| 车载探头校准凭证关联（requestId 幂等） | `POST /probes/bind` |
| 车辆当前绑定 / 替换历史 | `GET /probes/vehicles/{vehicleId}/current` / `.../history` |
| 管理页面 | `GET /ui/cold-chain/probes`（HTTP Basic） |

示例（建档→上传证书→幂等关联）：

```bash
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/probes \
  -d '{"serialNo":"SN-T-0001","model":"TP-200","rangeMin":-40,"rangeMax":60,
       "calibrateOrg":"华东计量院","validUntil":"2027-09-30T00:00:00",
       "certNo":"CAL-2026-0001","calibrateDate":"2026-09-29T10:00:00",
       "certFileName":"CAL-2026-0001.txt","certFileContent":"校准证书原文内容"}'

curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/probes/bind \
  -d '{"requestId":"b3f1-uuid","vehicleId":1,"probeId":1}'
# 再用完全相同的 requestId 请求一次：replay=true，bind.id 不变，绑定表不产生第二条结果
```

### V3 验收

`ProbeCalibrationIntegrationTest` 以真实 HTTP 入口驱动并用 Mapper 直查物理表：
冷链药品运输先提交有效样本（建档→订单→派车），再两次重复请求“车载探头校准凭证关联”，
断言两次返回同一条绑定且物理表只有一行绑定/一行幂等登记/一条绑定流水；
覆盖发运前未绑定/证书过期/证书作废拦截、绑定过期设备拦截、替换链前后关系、
在途批次历史温度不回写（新批次归新探头）、SHA-256 摘要确定性、下载/作废逐次留痕、
同 requestId 换参数拒绝，以及管理页面过期探头禁选与待发运批次拦截提示。

## 冷链运输 V4：批次分仓交接

在 V1~V3 台账上增加“一车货到中转仓后拆分交接”的全流程：一车货可拆成多个交接单，
每单记录箱数、逐箱封签号、交接温度与接收人；封签逐条核验通过、全批箱数平衡后提交，
责任主体随单转移；未完成核验的箱子不能继续派送。冷链药品运输权限被收紧时，
分仓交接停止推进，但已经确认的封签核验事实原样保留、仍可追溯。

### 核心规则

- **先到仓登记再拆单**：批次发运在途后先办理“到仓登记”，记录到仓总箱数，作为分箱平衡基准；
  到仓事实只登记一次，不可重复登记/修改。
- **一车多单拆箱**：一批货可连续拆成多张交接单，每单记录中转仓、箱数、交接温度、接收人及
  接收责任主体；逐箱封签，**封签数必须等于箱数**。
- **箱数必须平衡**：非作废交接单箱数合计在建单时不得超过到仓总箱数（超出给出超几箱并拒绝）；
  任一单提交时，全批有效交接单箱数合计必须**正好等于**到仓总箱数，差额未交接齐则禁止提交。
- **封签号批次内唯一**：同一运输批次范围内（含历史/已作废交接单）封签号不得重复，
  重复时指明与哪张交接单冲突；单内重复同样拒绝。
- **交接温度/接收人不得缺失**：建单即必须给出交接温度与接收人/接收责任主体，缺失直接拒绝。
- **封签逐条核验，事实只进不退**：封签状态 `PENDING -> CONFIRMED` 单向流转，记录核验说明、
  核验人与核验时间；已确认事实不能重复核验或改写。单内封签全部核验才能提交。
- **提交即责任主体随单转移**：提交置 `SUBMITTED` 并冻结箱数/封签/温度/接收人，
  单上责任主体由原承运批次（承运商/驾驶员）转移为接收机构/接收人，批次当前责任主体同步更新；
  原运输批次仍通过 `batch_id` 串联到仓、每张交接单、每枚封签核验与责任转移的全部节点。
- **未核验箱子不能继续派送**：只有当全部到仓箱数都已分到交接单、所在单均 `SUBMITTED`
  （即每箱封签都已核验）时，批次签收闭环/继续派送才放行；否则给出“还差几箱/几签未核验”。
- **作废不删事实**：仅 `DRAFT` 可作废为 `VOIDED`（已提交的责任转移不可撤销），作废释放箱数占用，
  但封签/核验明细与操作日志原样保留，封签号仍占用、不可复用。
- **权限收紧冻结推进、保留已确认事实**：`COLD_CHAIN_HANDOVER` 权限一旦收紧（`permitted=FALSE`），
  到仓/建单/封签核验/提交/作废/签收等一切推进动作停止并返回明确停点；查询不受影响，
  已确认的封签核验事实原样保留，恢复放行后无需重新核验即可继续。
- 到仓/建单/核验/提交/作废/权限收紧与恢复均写 `t_cc_op_log`（实体类型
  `HANDOVER` / `SEAL` / `PERMISSION`，并带 `batch_id` 串联）。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-4`）

- 新增 `t_cc_handover_order`（交接单：箱数/封签数/交接温度/接收人/责任主体快照/提交与作废）、
  `t_cc_handover_seal`（逐封签核验，批次内封签号唯一）、`t_cc_permission_setting`（权限开关），
  序列 `seq_cc_handover_no`（交接单号 `JJ+8位`）。
- `t_cc_transport_batch` 增 `arrived_box_qty`（到仓总箱数）、
  `responsible_org` / `responsible_party`（当前责任主体，发运为承运方、交接提交后随单转移）。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 |
| --- | --- |
| 一车货到仓登记（到仓总箱数） | `POST /handovers/arrive` |
| 拆交接单（箱数/封签/温度/接收人） | `POST /handovers` |
| 逐封签核验 | `POST /handovers/seals/verify` |
| 提交（责任主体随单转移） | `POST /handovers/{id}/submit` |
| 作废草稿单（事实保留） | `POST /handovers/void` |
| 交接单详情（含封签核验） | `GET /handovers/{id}` |
| 批次交接单列表 | `GET /handovers/batch/{batchId}` |
| 批次交接进度/能否继续派送 | `GET /handovers/batch/{batchId}/progress` |
| 收紧/恢复冷链运输权限 | `POST /permissions` |
| 查询冷链交接权限状态 | `GET /permissions/cold-chain-handover` |
| 批次完整台账（含全部分仓节点） | `GET /batches/{id}/ledger` |
| 分仓交接管理页面 | `GET /ui/cold-chain/handovers`（HTTP Basic） |

示例（到仓 100 箱后拆一单 60 箱，逐箱封签）：

```bash
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/handovers/arrive \
  -d '{"batchId":1,"arrivedBoxQty":100,"warehouseName":"华东中转仓"}'

curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/handovers \
  -d '{"batchId":1,"warehouseName":"华东中转仓","boxQty":60,"handoverTemp":5.20,
       "receiverName":"张仓","receiverOrg":"华东中转仓收货组",
       "sealNos":["SF001","SF002"]}'

# 逐封签核验（全部核验且箱数平衡后才能 /handovers/{id}/submit）
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/handovers/seals/verify \
  -d '{"sealId":1,"verifyNote":"封签完好、箱内温度达标"}'

# 收紧冷链药品运输权限：分仓交接停止推进，已确认封签事实保留
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/permissions \
  -d '{"permitted":false,"reason":"监管现场整改，暂停冷链交接"}'
```

### V4 验收

`HandoverTransferIntegrationTest` 以真实 HTTP 入口驱动并用 Mapper 直查物理表，覆盖：
一车货拆多单（6+4）、逐封签核验、提交责任主体随单转移、批次台账追溯到仓/交接单/封签全节点、
未核验箱子拦截继续派送与签收（补齐后放行）；箱数不平（箱签数不符/超分/未交齐）、封签重复
（单内与跨单且指明冲突单）、交接温度/接收人缺失三类停点均返回具体原因并禁止提交；
权限收紧后建单/核验/提交/签收全部停止、页面显示收紧横幅但已确认封签事实保留、恢复后无需重核；
作废释放箱数占用但封签/核验事实保留、封签号不可复用。


## 冷链运输 V5：按药品类别的温控预警模板

在 V1~V4 台账上增加“按药品类别树维护温控预警模板”：类别可继承上级温区阈值，也能在指定温区
显式覆盖；模板修改只影响此后新建批次，旧批次始终按派车（创建）时快照判定；系统按采样点计算
连续超限时长并合并短暂抖动，达到容忍时长产生追加式预警（不因模板变更消失）；预警查看/导出
等均经过 Shiro 权限点校验；定时任务重启后增量幂等扫描，不多写记录、不丢操作者。

### 核心规则

- **类别树 + 模板按温区覆盖**：`t_cc_drug_category` 以 `parent_id` 自引用成树（防环）；每个
  类别至多一份模板（`t_cc_alert_template`，维护即升版本），模板按温区行
  （`t_cc_alert_template_zone`）给阈值（温区上下限、连续超限**容忍时长**、**短暂抖动合并窗**）。
  温区行只追加：修改=旧行置 `SUPERSEDED` + 插入新生效行，历史原值永不修改。
- **继承上级阈值 / 指定温区覆盖**：类别未显式配置某温区且 `inheritParent=TRUE` 时，沿类别链
  向上继承最近一级已配置阈值（中间类别 `inheritParent=FALSE` 截断继续向上）；在指定温区显式
  提交阈值即覆盖上级。解析结果中 `inherited` 标记是否继承、`source_category_*` 标明阈值来源类别。
- **创建时快照，只影响新批次**：派车生成批次时按药品类别把解析后的生效模板逐温区固化到
  `t_cc_batch_alert_zone_snap` 并按药品储存温区命中本批次预警温区（`alert_zone_code`）。
  此后类别改名/移动、模板升版、阈值调整都与在途批次脱钩，旧批次的连续超限判定与预警追溯
  一律以创建时快照为准（与档案快照、路线分段快照同一口径）。
- **按采样点计算连续超限 + 短暂抖动合并**：每个采样点（批次在途探头全程唯一）按快照温区判定，
  计时状态落在 `t_cc_alert_streak`（批次×温区一行）。采样点短暂回正常且正常间隔 **不超过**
  模板“抖动合并窗”时视为抖动，连续超限合并、起点不变；超过合并窗才真正断开、新段重新计时。
  连续超限持续**超过**容忍时长产生一条 `t_cc_temp_alert`（一次连续段只产一条），并联动一条
  `TEMP_EXCURSION` 温度异常把批次挂起（沿用 V1 签收卡口）；预警记录段内峰值温度。
- **预警只追加、不随模板变更消失**：预警触发时把温区/容忍/抖动窗/来源类别等阈值快照复制进
  `t_cc_temp_alert`；闭环只追加 `resolve_*`（同时联动闭环对应温度异常），原描述不改、行不删，
  后续模板如何修改，已产生预警都原样保留。唯一键 `(batch_id, zone_code, start_time)` 兜底重复。
- **定时任务重启幂等、不丢操作者**：`AlertScanScheduler`（Spring `@Scheduled`，默认 60s）调用
  扫描服务，扫描游标 `t_cc_alert_scan_cursor` 记录已纳入计算的最大温度留痕ID，重启后增量续算；
  评估对每批做确定性重放（仅重建 streak 过程状态，不删温度/预警事实），因此**重启定时任务或
  游标从头重放都不会多写**预警/温度异常/产生流水；定时线程无登录主体时操作者统一记 `system`。
- **Shiro 权限校验**：Realm 升级为授权域并新增 `admin/admin123`。预警查看
  `coldchain:alert:view`、导出 `coldchain:alert:export`、闭环 `coldchain:alert:resolve`、
  手动扫描 `coldchain:alert:scan`、类别模板维护 `coldchain:template:manage` 逐路径校验；
  未认证 401、已登录无权限 403。**管理员查看和导出都必须经过 Shiro 权限校验**，导出逐次写
  审计流水。温度实时上报也走同一套评估（与上报同事务）。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-5`）

- 新增 `t_cc_drug_category`（类别树）、`t_cc_alert_template`（类别模板）、
  `t_cc_alert_template_zone`（温区行，只追加）、`t_cc_batch_alert_zone_snap`（批次创建时快照）、
  `t_cc_alert_streak`（连续超限计时状态）、`t_cc_temp_alert`（预警，只追加）、
  `t_cc_alert_scan_cursor`（定时扫描游标），序列 `seq_cc_category_no`（类别号 `LB+8位`）。
- `t_cc_drug` 增 `category_id`；`t_cc_transport_batch` 增 `alert_zone_code`；
  `t_cc_exception` 增 `alert_id`（联动预警）。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 | 权限 |
| --- | --- | --- |
| 类别建档 / 改名挂接 / 作废 | `POST /categories` / `POST /categories/{id}/change` / `POST /categories/{id}/revoke` | `coldchain:template:manage` |
| 类别树列表 / 详情 | `GET /categories/tree` / `GET /categories/{id}` | `coldchain:alert:view` |
| 维护类别预警模板（升版，携带要覆盖的温区） | `POST /categories/{id}/template` | `coldchain:template:manage` |
| 模板详情（含沿链继承解析视图） | `GET /categories/{id}/template` | `coldchain:alert:view` |
| 药品归组到类别 | `POST /drugs/{id}/category` | `coldchain:template:manage` |
| 预警列表 / 详情 | `GET /alerts` / `GET /alerts/{id}` | `coldchain:alert:view` |
| 预警处理闭环（联动闭环温度异常） | `POST /alerts/{id}/resolve` | `coldchain:alert:resolve` |
| 导出预警 CSV（UTF-8 BOM，逐次留痕） | `GET /alerts/export` | `coldchain:alert:export` |
| 手动触发一次扫描（同定时任务幂等逻辑） | `POST /alerts/scan` | `coldchain:alert:scan` |
| 预警 + 类别模板管理页面 | `GET /ui/cold-chain/alerts` | `coldchain:alert:view` |

建档时可直接带 `categoryId`；模板修改后只对新派车批次生效。示例（根类基线模板 + 子类继承）：

```bash
# 管理员建根类别并维护冷藏温区模板：2~8℃，连续超限容忍30分钟，短暂抖动合并窗10分钟
curl -u admin:admin123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/categories -d '{"categoryName":"冷藏药品"}'

curl -u admin:admin123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/categories/1/template \
  -d '{"inheritParent":true,"changeReason":"夏季基线",
       "zones":[{"zoneCode":"REFRIGERATE","zoneName":"冷藏温区","tempMin":2,"tempMax":8,
                 "tolerateMinutes":30,"jitterGapMinutes":10}]}'

# 建子类别（不配置温区即继承根类）；药品建档带 categoryId；之后正常 订单→派车→发运→温度上报
curl -u admin:admin123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/categories \
  -d '{"categoryName":"疫苗","parentId":1}'

# 管理员导出预警（需导出权限，逐次写审计流水）
curl -u admin:admin123 -OJ http://localhost:8080/api/cold-chain/alerts/export
```

定时任务可通过配置开关：`coldchain.alert-scan.enabled`（默认 `true`）、
`coldchain.alert-scan.fixed-delay-ms`（默认 60000）、`initial-delay-ms`（默认 15000）。

### V5 验收

`CategoryAlertTemplateIntegrationTest` 以真实 HTTP 入口（TestRestTemplate + Basic）验证 Shiro
权限（401/403/200、CSV BOM、导出留痕操作者），再用 Mapper 直查物理表，覆盖：
类别阈值继承与指定温区覆盖、派车创建时快照（标明继承来源）、模板修改只影响新批次而旧批次快照
与已产生预警不回写不消失、按采样点连续超限时长（短暂抖动≤合并窗合并且起点不变、对照超窗正常
段打断重计）、一次连续段只产一条预警；以及**重启定时任务/扫描游标从头重放后“预警阈值按药品
类别继承”不多写任何预警/异常/流水、操作者始终为 system 不丢**。

---

## 冷链运输 V6：运输异常分级与处置时钟

在 V1~V5 台账上建立运输异常分级机制，覆盖**温度超限、封签破损、延误、收货拒签**四类异常：
按类型配置等级（L1 重大 / L2 较大 / L3 一般）、响应时限、处置时限、处置岗位、升级岗位与
关闭前复核岗位；异常确认即启动处置时钟；升级通知逐次记录发送结果与失败/重试原因；处置结论
必须引用证据并由指定角色复核才能关闭；未关闭异常拦截签收与结算。

### 核心规则

- **分级配置与确认时快照**：`t_cc_exception_grade_rule` 四类各一行（默认：温度超限 L1
  30/120 分钟 承运→调度、封签破损 L2 60/240 分钟 收货→调度、延误 L3 120/480 分钟 承运→调度、
  收货拒签 L1 30/180 分钟 调度→管理员；复核岗统一为 QA）。规则可随时调整，但**只影响此后确认
  的异常**——确认瞬间把等级/时限/岗位逐字段快照到异常行，在途时钟与档案快照、路线分段快照、
  预警模板快照同一口径，不随后续改规则变动。
- **确认才启钟，补录不覆盖首次发现时间**：人工补录为 `OPEN`（待确认，时钟不启动），显式确认后
  置 `CONFIRMED`，`confirm_time`=确认时刻，`response_due_time`/`resolve_due_time`=确认时刻+快照
  时限；`found_time` 只在异常首次落库写一次，确认/补录/重放/重试均不覆盖。系统客观判定成立的
  异常（温度达到临界、封签核验破损、收货拒签）**成立即确认**。
- **温度分级调到临界时间，处置时钟同一口径归属**：温度异常的 `found_time` 与 `confirm_time`
  都取“连续超限达到容忍时长的临界采样时刻”，与 `t_cc_temp_alert.first_found_time`
  （= `trigger_time`）严格同值；截止时间 = 临界时刻 + 快照时限。预警闭环不再越级关闭温度异常，
  后者必须走引用证据 + QA 复核关闭。
- **升级通知只追加**：`t_cc_exception_notify_log` 每次发送/重试独立一行，记录目标岗位、阶段
  （RESPONSE/ESCALATE/RETRY）、尝试次序、渠道（SITE 站内信 / WEBHOOK）、成功/失败结果；
  失败必填 `fail_reason`，重试必填 `retry_reason`，历史失败行保留。无 endpoint 走站内信必达；
  配了 webhook 走 HTTP 通道（超时/非 2xx 即失败）。定时扫描对“最近一次仍失败”的通知自动补发。
- **结论引用证据 + 指定角色复核**：提交处置结论必须引用至少一条本异常名下的凭证ID，进入
  `REVIEWING`；只能由规则快照的复核岗位（默认 QA）复核：通过才 `RESOLVED` 关闭，驳回退回
  `CONFIRMED`（时钟不重启、截止不变，历史复核意见在 `t_cc_exception_review_log` 逐次追加）。
  分级四类异常不能再用一句话直接闭环（旧类型 DAMAGE/OTHER 保留旧闭环）。
- **未关闭不能签收、不能结算**：OPEN/CONFIRMED/REVIEWING 全部计入未关闭数，拦截签收；
  `POST /batches/settle` 要求批次已签收且未关闭数为 0，`settle_time/settle_by/settle_remark`
  只记账一次。收货拒签（`POST /batches/reject`）成立即重大 L1 异常，批次不进入签收，须处置关闭。
- **逾期升级**：处置截止逾期仍未关闭的 CONFIRMED 异常，定时任务置升级事实（只进不退，每异常
  一次）并通知升级岗位；已撤回批次停止督办。开关 `coldchain.exception-clock.enabled`（默认 true）。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-6`）

- 新增 `t_cc_exception_grade_rule`（四类分级规则，含四条默认数据）、
  `t_cc_exception_notify_log`（通知发送/重试只追加）、
  `t_cc_exception_review_log`（复核意见只追加）。
- `t_cc_exception` 增 `found_time/found_source/grade/grade_name/*_minutes_snap/*_role_snap/
  confirm_by/confirm_time/response_due_time/resolve_due_time/escalated/escalate_time/conclusion/
  evidence_refs/review_status/review_by/review_time/review_note`；
  `t_cc_temp_alert` 增 `first_found_time`（临界时刻）；
  `t_cc_transport_batch` 增 `settle_time/settle_by/settle_remark`。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 | 权限 |
| --- | --- | --- |
| 分级规则查看 / 调整（只影响此后确认的异常） | `GET/POST /exceptions/grade-rules` | `coldchain:exception:rule:manage` |
| 确认补录异常（定级快照+启钟+首响通知） | `POST /exceptions/{id}/confirm` | `coldchain:exception:confirm` |
| 提交处置结论（必须引用证据） | `POST /exceptions/{id}/conclude` | `coldchain:exception:conclude` |
| 关闭前复核（通过/驳回，须 QA 角色） | `POST /exceptions/{id}/review` | `coldchain:exception:review` |
| 通知手动重试（必填重试原因） | `POST /exceptions/{id}/notify-retry` | `coldchain:exception:notify:retry` |
| 异常处置详情（通知记录+复核记录） | `GET /exceptions/{id}` | 登录即可 |
| 手动触发时钟扫描（逾期升级+失败重试） | `POST /exceptions/clock/scan` | `coldchain:exception:rule:manage` |
| 收货拒签（成立即 L1 确认启钟） | `POST /batches/reject` | `coldchain:exception:confirm` |
| 批次结算（已签收且无未关闭异常） | `POST /batches/settle` | `coldchain:batch:settle` |

示例（延误补录 → 确认 → 引用证据的结论 → QA 复核 → 签收 → 结算）：

```bash
# 调度补录一条延误（OPEN 待确认，时钟不启动；occurredTime=首次发现时间，只写一次）
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/batches/exceptions -d \
  '{"batchId":1,"exceptionType":"DELAY","description":"高速管制延误2小时",
    "occurredTime":"2026-10-03T09:00:00"}'

# 确认启钟（按当前 DELAY 规则定级，时钟自确认时刻起算）
curl -u dispatcher:dispatcher123 -X POST \
  http://localhost:8080/api/cold-chain/exceptions/101/confirm

# 追加证据并在结论中引用（evidenceIds 必须属于本异常）
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/batches/exceptions/evidence -d \
  '{"exceptionId":101,"fileName":"traffic.png","fileUrl":"/v/traffic","note":"管制通告截图"}'
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/exceptions/101/conclude -d \
  '{"conclusion":"道路管制导致延误，温控全程达标，药品质量无影响","evidenceIds":[201]}'

# 只能由 QA 岗位复核关闭（通过/驳回都必须留复核意见）
curl -u qa:qa123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/exceptions/101/review -d \
  '{"approved":true,"reviewNote":"管制通告与温控曲线齐备，同意关闭"}'

# 全部异常复核关闭后签收，再结算（未关闭异常会被拦截）
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/batches/settle -d '{"batchId":1,"settleRemark":"10月运费结算"}'
```

### V6 验收

`ExceptionGradingLifecycleIntegrationTest`（5 个用例，内存 H2 + 可控通知通道替身）覆盖：
四类分级快照与规则调整只影响新异常、确认才启钟且补录不覆盖首次发现时间、温度临界时间与
处置时钟同一口径（foundTime=confirmTime=预警 firstFoundTime）、通知失败原因与手动/定时重试
原因逐次留痕且 attempt 递增、逾期升级一次且不重复、结论必须引用本异常证据、非 QA 角色禁止
复核、驳回后时钟不重启可重处、QA 通过才关闭、未关闭异常拦截签收与结算、收货拒签即 L1；
以及 Shiro 权限点的真实 HTTP 校验（401/403/200）。既有 V1/V5 测试同步改为新复核闭环口径，
全套 26 个用例通过。

## 冷链运输 V7：药品运输抽检与运输审批

在 V1~V6 台账上新增监管侧“药品运输抽检”：监管人员按运输批次抽取样本、生成唯一封存码并
记录采样人/位置/数量/封存照片；样本转移双人确认；已封存样本不可删除；查询按监管区域隔离；
导出脱敏承运人联系方式；抽检结论回写批次但不覆盖原始运输记录。冷链药品运输审批两步走，
只完成一半时停留待处理，审批期间抽检任务与样本封存照常可办、全程可追溯。

### 核心规则

- **按批次立项、区域归属**：抽检任务挂在运输批次下并归属监管区域；区域可在派车时用
  `superviseRegion` 指定，也可在首次抽检时由账号唯一授权区域补登（一经确定不再改变）。
  监管账号的区域授权在 `t_cc_inspector_region`（可多区，`*` 为全域，仅 admin）。
- **唯一封存码 + 封存四要素**：封存样本时由序列生成封存码 `FC年+8位序列`，数据库唯一约束
  兜底、永不复用；封存当刻固化采样人、采样位置、数量（>0）与封存照片（名称/地址/内容
  SHA-256 摘要）。
- **样本只进不退、不可删除**：样本状态只沿 `SEALED -> TRANSFERRED` 前进，当前持有方随
  双人确认转移；系统不提供任何修改/删除样本或照片的接口；任务已定稿不能再封存样本。
- **转移双人确认**：移交人发起即落移交人确认，单据停留 `PENDING`；必须由**不同于移交人的
  第二个登录账号**确认才 `CONFIRMED`，持有方随之转移。一个样本同时只允许一条 PENDING 转移；
  每次转移追加一行形成完整转移链，确认事实不可重复/改写。
- **查询按监管区域隔离**：列表/详情/按批次查询/导出都先解析当前账号授权区域，越权访问
  直接拒绝；无区域授权查不到任何数据。
- **导出脱敏**：抽检样本 CSV 只导授权区域数据，承运人联系方式（驾驶员电话）脱敏为
  `前3位+******+后2位`（如 `13912345678 -> 139******78`），导出逐次写审计流水。
- **结论回写批次但不覆盖原始运输记录**：抽检结论（PASS/FAIL + 说明）只写批次的独立列
  `inspection_*`，不改动批次状态、温度、异常、交接、结算等任何原始运输记录；结论一次定稿。
- **运输审批两步、半成品停留**：`POST /approvals` 申请后停留 `PENDING`（只完成一半），
  经监管岗 `approve` 同意才 `APPROVED`、批次方可发运；`reject` 驳回停留 `REJECTED`，
  可重新申请。PENDING/REJECTED 都在发运卡口拦截。审批进行期间抽检立项、样本封存、双人
  转移照常办理、可追溯。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-7`）

- 新增 `t_cc_inspector_region`（监管账号-区域授权，含演示种子）、
  `t_cc_inspection_task`（抽检任务）、`t_cc_inspection_sample`（样本/封存码/照片）、
  `t_cc_sample_transfer`（双人转移单）、`t_cc_inspection_approval`（运输审批）；
  序列 `seq_cc_inspection_no/seq_cc_seal_no/seq_cc_approval_no`。
- `t_cc_transport_batch` 增 `supervise_region/inspection_task_id/inspection_result/
  inspection_conclusion/inspection_by/inspection_time`（独立抽检列，不覆盖原始运输记录）。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 | 权限 |
| --- | --- | --- |
| 立项抽检任务（按批次+区域） | `POST /inspections` | `coldchain:inspection:manage` |
| 封存样本（唯一封存码+四要素） | `POST /inspections/samples/seal` | `coldchain:inspection:manage` |
| 移交发起（停留待接收确认） | `POST /inspections/samples/transfers/request` | `coldchain:inspection:manage` |
| 接收人第二账号确认 | `POST /inspections/samples/transfers/{id}/confirm` | `coldchain:inspection:manage` |
| 出具结论（回写批次独立列） | `POST /inspections/{id}/conclude` | `coldchain:inspection:manage` |
| 任务列表/详情/按批次查询（区域隔离） | `GET /inspections`、`GET /inspections/{id}`、`GET /inspections/batch/{batchId}` | `coldchain:inspection:view` |
| 导出样本 CSV（区域隔离+电话脱敏） | `GET /inspections/export` | `coldchain:inspection:export` |
| 运输审批申请 | `POST /approvals` | `coldchain:approval:apply` |
| 审批同意 / 驳回 | `POST /approvals/{id}/approve`、`/reject` | `coldchain:approval:review` |
| 待处理审批 / 批次审批链 / 详情 | `GET /approvals/pending`、`/batch/{batchId}`、`/{id}` | 审批权限 / 登录 |

示例（监管人封存样本、双人转移、出结论）：

```bash
# 立项（区域必须在 inspector 授权范围内）
curl -u inspector:inspector123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/inspections -d \
  '{"batchId":1,"superviseRegion":"EAST","taskName":"在途疫苗随机抽检",
    "samplingLocation":"沪杭高速嘉兴服务区冷链车车厢","samplerName":"周监"}'

# 封存样本：返回唯一封存码、固化采样人/位置/数量/封存照片
curl -u inspector:inspector123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/inspections/samples/seal -d \
  '{"taskId":1,"quantity":3,"sampleLocation":"车厢上层A位","samplerName":"周监",
    "sealPhotoName":"seal1.jpg","sealPhotoUrl":"/v7/seal1","sealPhotoContent":"照片字节"}'

# 移交发起（inspector），再由另一个账号（如 admin）确认；同一账号自确认会被拒绝
curl -u inspector:inspector123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/inspections/samples/transfers/request -d \
  '{"sampleId":1,"toCustodianName":"检验所收件员郑检","toCustodianOrg":"市药检所收样室",
    "handoverLocation":"嘉兴服务区"}'
curl -u admin:admin123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/inspections/samples/transfers/1/confirm -d \
  '{"receiverNote":"封签完好，冷链温度达标"}'

# 结论回写批次独立列（不改原始运输记录）
curl -u inspector:inspector123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/inspections/1/conclude -d \
  '{"result":"PASS","conclusion":"温控链完整、包装完好，抽检合格"}'

# 导出（承运人电话脱敏）
curl -u inspector:inspector123 http://localhost:8080/api/cold-chain/inspections/export?region=EAST
```

示例（冷链运输审批两步；半成品停留期间不能发运，但抽检照常）：

```bash
# 调度申请 -> PENDING
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/approvals -d \
  '{"batchId":1,"reason":"疫苗冷链运输放行申请"}'
# 监管同意后才能发运；未同意前 /batches/1/ship 被“只完成一半/停留待处理”拦截
curl -u inspector:inspector123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/approvals/1/approve -d '{"reviewNote":"同意放行"}'
```

### V7 验收

`InspectionSamplingIntegrationTest`（5 个用例，内存 H2 + 真实 Shiro HTTP Basic）覆盖：
唯一封存码与采样人/位置/数量/封存照片留痕、同一账号自确认拒绝与第二账号双人确认转移、
待确认期间禁止二次移交、已封存样本无删除路径、结论回写批次独立列且批次状态/数量/签收/派车
时间等原始运输记录不变、结论一次定稿、批次台账可追溯任务-样本-双人转移链；查询/详情/按批次
查询跨区域隔离（EAST 账号看不到 SOUTH）；导出 CSV 电话脱敏为 `139******78` 且无导出权限
返回 403；运输审批半成品 PENDING 拦截发运、审批期间抽检封存照常、同意后放行、驳回可重申；
以及无抽检/审批权限账号的 403 校验。全套 33 个用例通过。

## 冷链运输 V8：冷链承运商报价比选与锁价

在 V1~V7 台账上新增“冷链承运商报价比选”：发起一次询价（报价批次）后多家承运商按
**路线 × 温区 × 箱型 × 服务等级**并行报价；报价可在截止前修订（只追加新版本行）；
比选后锁价单**只能选定一个有效方案**并保存全要素快照；报价过期后不能被新批次引用/锁价；
金额固定拆出**基础运费 + 逐项附加费 + 税率**；锁价后人工改价**必须经独立审批**，
原始报价与原始附加费快照永久保留；从冷链药品运输的锁价单可反查到原报价单、处理人与异常原因。

### 核心规则

- **承运商独立主档**：`t_cc_carrier` 与车辆表自由文本承运商名解耦；一家承运商可持多车、
  并行参与多个报价批次。
- **报价按四维计算**：报价批次 `t_cc_quote_batch` 固化路线（无路线则起终点）、温区
  （REFRIGERATE/FROZEN/DEEP_FROZEN/…）、箱型（冷藏箱/保温箱/整车/…）、服务等级
  （标准/加急/专车/…）、箱量与报价截止时间；维度代码必须出自代码表，未知代码拒绝。
- **多承运商并行报价、修订只追加**：OPEN 期间各承运商独立提交互不影响；同一承运商重复提交
  即生成新版本行（version_no 递增），旧版本置 `SUPERSEDED`，原值与原附加费明细永不修改。
- **报价过期不能被新批次引用**：到截止时间 OPEN 批次懒标记（报价/比选/锁价路径同步检查）
  + 定时扫描（默认 60s）双保险置 `EXPIRED`，其当前报价全部失效；过期后拒绝再提交/修订，
  拒绝锁价，新批次也无法引用旧报价单。人工提前截止置 `CLOSED`（已报方案仍可锁价）。
- **锁价单只选一个有效方案 + 全要素快照**：`t_cc_locked_price` 对报价批次唯一约束兜底；
  中选报价 `LOCKED`、其余落选 `REJECTED`、批次转 `CLOSED`，不能二次锁价。锁价瞬间把
  路线/温区/箱型/服务等级/承运商/箱量/金额拆项/有效期逐字段快照，附加费逐行复制，
  此后报价修订、过期都与锁价单脱钩。
- **金额拆出附加费和税率**：调用方只能提交基础运费、附加费明细、税率；系统统一重算
  不含税小计=基础+附加费、税额=小计×税率、含税总价=小计+税额，不接受一口价总价。
- **人工改价必须审批且保留原始报价**：锁价后无直接改金额路径；发起改价落 `PENDING`、
  锁价单转 `ADJUSTING`（期间派车卡口拦截），由 QA 岗同意后 `final_*` 才生效；
  驳回则继续按原价执行。锁价单 `*_snap` 原始金额、原报价行、原始附加费快照永不覆盖，
  每次申请（含被驳回）的改后金额与附加费行只追加保留。申请人（调度）与审批人（QA）职责分离。
- **锁价单反查**：批次派车可携带锁价单（与 V7 审批同口径，携带即必须有效）；
  `GET /locked-prices/{id}/trace` 与 `GET /locked-prices/by-batch/{batchId}` 沿
  锁价单 → 中选原报价（含该承运商全部历版）→ 报价批次（含同批全部报价）→ 承运商 →
  改价审批链一路返回原单号、各节点处理人/时间与异常原因（过期失效、落选、修订替换、
  改价驳回、锁价单作废）；批次台账 `/batches/{id}/ledger` 内含同一反查块。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-8`）

- 新增 `t_cc_carrier`（承运商主档，含两条演示种子）、`t_cc_quote_batch`（报价批次）、
  `t_cc_carrier_quote`（报价单，只追加修订版本）、`t_cc_quote_surcharge`（附加费明细，
  含锁价快照/改价附加费）、`t_cc_locked_price`（锁价单，唯一批次+原始/生效双套金额）、
  `t_cc_quote_adjust_approval`（改价审批，只追加）；
  序列 `seq_cc_carrier_no/seq_cc_quote_batch_no/seq_cc_locked_price_no`
  （编号 `CY/BJ/SJ` + 9 位）。
- `t_cc_vehicle` 增 `carrier_id`；`t_cc_transport_batch` 增 `locked_price_id`（反查锚点）。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 | 权限 |
| --- | --- | --- |
| 承运商建档 / 列表 | `POST /carriers/create` / `GET /carriers` | `coldchain:carrier:manage` / 查看 |
| 发起报价批次（询价） | `POST /quotes/batches/open` | `coldchain:quote:batch:manage` |
| 人工截止 / 过期扫描 | `POST /quotes/batches/{id}/close` / `POST /quotes/expire-scan` | 同上 |
| 承运商提交/修订报价 | `POST /quotes/submit` | `coldchain:quote:submit` |
| 批次详情 / 比选排名 / 列表 | `GET /quotes/batches/{id}`、`/compare`、`/quotes/batches` | `coldchain:quote:view` |
| 比选锁价（唯一中选+快照） | `POST /locked-prices/lock` | `coldchain:price:lock` |
| 作废锁价单（已被批次引用拒绝） | `POST /locked-prices/{id}/void` | 同上 |
| 锁价单详情 / 反查 / 按批次反查 | `GET /locked-prices/{id}`、`/{id}/trace`、`/by-batch/{batchId}` | 查看 |
| 人工改价申请 | `POST /locked-prices/adjustments` | `coldchain:price:adjust:apply` |
| 改价同意 / 驳回 / 待审批列表 | `POST /locked-prices/adjustments/{id}/approve`、`/reject`、`GET .../pending` | `coldchain:price:adjust:review` |
| 派车携带锁价单 | `POST /batches/dispatch`（body 增加 `lockedPriceId`） | 调度 |

示例（询价 → 两家并行报价 → 锁价 → 派车）：

```bash
# 调度发起询价：上海→杭州，冷藏温区，保温箱，专车直达，2小时后截止
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/quotes/batches/open -d \
  '{"origin":"上海中心仓","destination":"杭州市一院","tempZoneCode":"REFRIGERATE",
    "boxType":"INSULATED_BOX","serviceLevel":"DEDICATED","boxQty":20,
    "quoteExpireTime":"2026-10-03T19:00:00"}'

# 承运商并行报价：基础运费 + 附加费明细 + 税率（小计/税额/总价由系统重算）
curl -u carrier:carrier123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/quotes/submit -d \
  '{"quoteBatchId":1,"carrierId":1,"baseAmount":1000.00,"taxRate":0.09,
    "validUntil":"2026-10-03T18:30:00",
    "surcharges":[{"surchargeType":"FUEL","amount":60.00},
                  {"surchargeType":"COLD","amount":40.00}]}'

# 比选排名
curl -u dispatcher:dispatcher123 http://localhost:8080/api/cold-chain/quotes/batches/1/compare

# 锁价（只能选一个有效方案；金额与附加费逐行快照）
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/locked-prices/lock -d \
  '{"quoteBatchId":1,"quoteId":1}'

# 锁价后人工改价：调度申请 -> QA 审批（驳回继续按原价；同意后 final_* 生效、原始报价保留）
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/locked-prices/adjustments -d \
  '{"lockedPriceId":1,"reason":"回程货源稳定，协商降价","newBaseAmount":700.00,
    "newTaxRate":0.06,"newSurcharges":[{"surchargeType":"FUEL","amount":10.00}]}'
curl -u qa:qa123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/locked-prices/adjustments/1/approve -d \
  '{"reviewNote":"依据充分，同意"}'

# 从冷链药品运输批次反查承运商报价（原单/处理人/异常原因）
curl -u dispatcher:dispatcher123 \
  http://localhost:8080/api/cold-chain/locked-prices/by-batch/1
```

定时过期扫描可通过配置开关：`coldchain.quote-expire.enabled`（默认 `true`）、
`coldchain.quote-expire.fixed-delay-ms`（默认 60000）、`initial-delay-ms`（默认 10000）。

### V8 验收

`CarrierQuoteLockIntegrationTest`（6 个用例，内存 H2 + 真实 Shiro HTTP Basic，Mapper 直查物理表）
覆盖：四家维度代码表校验、两家承运商并行报价与截止前修订只追加（旧版原值/原附加费不覆盖）、
金额拆项系统重算（基础+附加费/税额/含税总价）、比选按含税总价排名；锁价唯一中选（LOCKED/
落选 REJECTED/批次 CLOSED/重复锁价拒绝）、锁价后不可再修订、全要素与附加费逐行快照、
已被批次引用的锁价单不可作废；报价过期（回拨时间+扫描）后提交/锁价/被新批次引用全部拒绝；
人工改价 PENDING 拦截派车、承运商/调度自批 403、QA 驳回按原价执行、同意后 final_* 生效而
原始快照与原报价行不覆盖（驳回申请的附加费行也保留）；锁价单/批次两入口反查定位原单号、
报价人/锁价人/改价审批人与落选、改价驳回等异常原因，批次台账含反查块；以及未知维度、
税率越界、负金额、有效期越界、停用承运商等停点与 401/403 权限校验。全套 39 个用例通过。

## 冷链运输 V9：车载设备温度数据接入

在 V1~V8 台账上新增"车载设备温度数据接入"：设备断网恢复后批量补传温度数据，
服务端按设备序列和采样时间去重；每批数据携带签名和设备证书，验证失败进入隔离队列；
补传数据不能改变已经审核的异常结论；运维人员只能重放未入账数据，重放全过程写入审计记录；
补传、补录和重试只能形成一条可用结果。

### 核心规则

- **设备证书登记**：每台车载设备登记签名证书（device_serial → cert_fingerprint, cert_content,
  status, valid_until）。证书只追加不删除，作废置 REVOKED + 原因/时间。
- **批量数据接入**：设备断网恢复后批量补传。每批数据携带 requestId（幂等键）、deviceSerial、
  certFingerprint、signature、数据点数组（recordTime + temperature + mileageKm?）。
- **签名验证**：服务端按登记的证书重新计算 HMAC-SHA256 签名并比对。验证失败（证书未登记/
  过期/作废/指纹不匹配/签名不匹配/数据格式错误）→ 进入隔离队列，返回可读错误。
- **按设备序列+采样时间去重**：`t_cc_temp_dedup` 登记 (device_serial, record_time) → log_id，
  唯一约束兜底。重复点跳过（计入 duplicate_count），新点入账（计入 accepted_count）。
- **补传不改变已审核结论**：补传的历史数据点（record_time 早于已审核关闭异常的处理时间）
  标记 alert_exempt=TRUE，不参与预警评估，不产生新异常。已审核的异常结论
  （status=RESOLVED, reviewStatus=APPROVED）所有字段不变。
- **幂等（只能形成一条可用结果）**：同 requestId 重复请求 → 返回第一次结果（replay=true）；
  同 requestId 不同参数 → 拒绝；同 (device_serial, record_time) → 去重，只入账一次。
  补传/补录/重试收敛到同一条温度留痕。
- **隔离队列**：验证失败的批次进入隔离队列，记录原因代码和可读详情。运维修复问题后可重放。
- **重放审计**：重放只针对未入账（PENDING）的隔离数据。每次重放写 `t_cc_device_replay_log`
  （操作者、时间、结果、入账点数）。已入账的隔离数据不能重复重放。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-9`）

- 新增 `t_cc_device_cert`（设备签名证书）、`t_cc_device_ingest_batch`（数据批次登记，
  requestId 幂等键唯一）、`t_cc_temp_dedup`（温度去重登记，device_serial+record_time 唯一）、
  `t_cc_device_quarantine`（隔离队列）、`t_cc_device_replay_log`（重放审计，只追加）。
- `t_cc_temperature_log` 增 `source`（REALTIME/BACKFILL）、`ingest_batch_id`、`alert_exempt`。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 | 权限 |
| --- | --- | --- |
| 设备证书登记 / 列表 | `POST /device/certs` / `GET /device/certs` | `coldchain:device:cert:manage` |
| 设备温度批量上报 | `POST /device/temperatures` | `coldchain:device:ingest` |
| 数据批次列表 / 详情 | `GET /device/ingest-batches` / `GET /device/ingest-batches/{id}` | `coldchain:device:quarantine:view` |
| 隔离队列列表 / 详情 | `GET /device/quarantine` / `GET /device/quarantine/{id}` | `coldchain:device:quarantine:view` |
| 重放隔离数据 | `POST /device/quarantine/{id}/replay` | `coldchain:device:replay` |
| 重放审计记录 | `GET /device/quarantine/{id}/replay-logs` | `coldchain:device:quarantine:view` |

新增账号：`device/device123`（车载设备网关，仅数据上报权限）。

示例（证书登记 → 批量上报 → 隔离 → 重放）：

```bash
# 管理员登记设备证书
curl -u admin:admin123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/device/certs -d \
  '{"deviceSerial":"SN-T-0001","certFingerprint":"abc123...","certContent":"device-secret-key",
    "validUntil":"2027-12-31T23:59:59"}'

# 设备批量上报温度（断网恢复后补传）
curl -u device:device123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/device/temperatures -d \
  '{"requestId":"req-001","deviceSerial":"SN-T-0001","certFingerprint":"abc123...",
    "signature":"hmac-sha256-hex","batchId":1,
    "points":[{"recordTime":"2026-10-03T10:00:00","temperature":"5.20"},
              {"recordTime":"2026-10-03T11:00:00","temperature":"6.10"}]}'

# 查看隔离队列
curl -u admin:admin123 http://localhost:8080/api/cold-chain/device/quarantine

# 重放隔离数据（修复证书后）
curl -u admin:admin123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/device/quarantine/1/replay -d \
  '{"replayReason":"证书已补登记，重放数据"}'
```

### V9 验收

`DeviceTemperatureIngestIntegrationTest`（7 个用例，内存 H2 + 真实 Shiro HTTP Basic，
Mapper 直查物理表）覆盖：设备证书登记与签名验证通过后的正常批量上报（温度留痕+dedup 登记）；
断网补传去重（同 device_serial+record_time 只入账一次，重复点跳过）；幂等（同 requestId
重复上报返回第一次结果 replay=true，不同参数拒绝）；签名不匹配/证书未登记/过期进入隔离队列
并返回可读错误；重放成功（修复证书后）+ 审计记录 + 已入账不能重复重放；补传不改变已审核
异常结论（alert_exempt=TRUE，原异常 RESOLVED/APPROVED 不变，无新异常产生）；
权限校验（401 未认证 / 403 无权限 / 200 正常）。全套 46 个用例通过。
