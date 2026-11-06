// Bothy's protocol as TypeScript types, generated from bothy-protocol by
// `./gradlew :bothy-protocol:generateSchema`; do not edit.
// JSON-RPC 2.0 without the "jsonrpc" member, one message per line; unknown members are ignored and null ones
// left out. A sealed type's member with a `type` of its own is named after both ("StateSectionRun" is the
// `run` section).

export declare const PROTOCOL_VERSION: 1;

export interface A2aClientAnswerParams {
  questionId: string;
  text: string;
  source: A2aInputSource;
}

export type A2aClientItem =
  | ({ type: "display" } & A2aClientItemDisplay)
  | ({ type: "notice" } & A2aClientItemNotice)
  | ({ type: "question" } & A2aClientItemQuestion)
  | ({ type: "speech" } & A2aClientItemSpeech)
  | ({ type: "status" } & A2aClientItemStatus)
  | ({ type: "terminal" } & A2aClientItemTerminal)
  | ({ type: "tool" } & A2aClientItemTool)
  | ({ type: "user" } & A2aClientItemUser);

export interface A2aClientItemDisplay {
  id: number;
  taskId: string;
  text: string;
}

export interface A2aClientItemNotice {
  id: number;
  text: string;
  error?: boolean;
}

export interface A2aClientItemQuestion {
  id: number;
  taskId: string;
  questionId: string;
  text: string;
  options: Array<string>;
  phase: string;
}

export interface A2aClientItemSpeech {
  id: number;
  taskId: string;
  text: string;
  final: boolean;
}

export interface A2aClientItemStatus {
  id: number;
  taskId: string;
  text: string;
}

export interface A2aClientItemTerminal {
  id: number;
  taskId: string;
  state: A2aTaskStatus;
  text: string;
  elapsedMillis?: number | null;
}

export interface A2aClientItemTool {
  id: number;
  taskId: string;
  callId: string;
  name: string;
  phase: string;
  startedAtEpochMillis: number;
  finishedAtEpochMillis?: number | null;
}

export interface A2aClientItemUser {
  id: number;
  text: string;
  source: A2aInputSource;
  kind: Kind;
}

export interface A2aClientState {
  enabled?: boolean;
  speakExternal?: boolean;
  endpoint?: string;
  speechReady?: boolean;
  contextId?: string | null;
  running?: boolean;
  activeTaskId?: string | null;
  lastTaskId?: string | null;
  runStartedAtEpochMillis?: number | null;
  mirrors?: Record<string, number>;
  lastOutcome?: A2aOutcome | null;
  items?: Array<A2aClientItem>;
  nextItemId?: number;
  inboundFocus?: boolean;
}

export interface A2aClientTextParams {
  text: string;
  source: A2aInputSource;
}

export type A2aInputSource = "CONSOLE" | "OVERLAY" | "ADB" | "ASR" | "PC" | "EXTERNAL";

export interface A2aModuleState {
  profiles?: Array<A2aProfile>;
  activeProfileId?: string | null;
  loadedModuleId?: string | null;
  error?: string | null;
  enabledProfileIds?: Array<string>;
  loadedModuleIds?: Record<string, string>;
  errorsByProfileId?: Record<string, string>;
  tokenProfileIds?: Array<string>;
  moduleIds?: Array<string>;
}

export interface A2aOutcome {
  status: A2aTaskStatus;
  elapsedMillis?: number | null;
}

export interface A2aProfile {
  id: string;
  label: string;
  moduleId: string;
  endpoint: string;
  enabled: boolean;
  allowLoopbackHttpForDebug: boolean;
  userDefined?: boolean;
  description?: string;
}

export interface A2aProfileDraft {
  label: string;
  moduleId: string;
  endpoint: string;
  allowLoopback: boolean;
  description?: string;
  token?: string;
}

export type A2aTaskStatus = "SUBMITTED" | "WORKING" | "INPUT_REQUIRED" | "COMPLETED" | "CANCELLED" | "FAILED" | "UNKNOWN";

export type AndroidUseState = "OFF" | "CONNECTED" | "WAITING" | "FAILED";

export interface AndroidUseView {
  enabled?: boolean;
  state?: AndroidUseState;
}

export type ApprovalDecision = "approve" | "deny";

export interface ApprovalRequest {
  threadId: string;
  itemId: number;
  pendingId: string;
  domain?: string;
  mode: ConfirmationMode;
  toolName?: string;
  argumentsJson?: string | null;
}

export interface ApprovalResolved {
  pendingId: string;
  status: ConfirmationStatus;
}

export interface ApprovalRespondParams {
  pendingId: string;
  decision: ApprovalDecision;
}

export interface ApprovalResponse {
  decision: ApprovalDecision;
}

export interface ArtifactPreview {
  path: string;
  html: string;
  bytes: number;
  refreshToken: string;
}

export interface BalanceAmountView {
  currency: string;
  total: string;
  toppedUp: string;
  granted: string;
}

export interface BalanceView {
  available: boolean;
  amounts?: Array<BalanceAmountView>;
}

export interface BasicToolView {
  name: string;
  description: string;
  enabled: boolean;
  required?: boolean;
}

export interface BoundCall {
  providerId: string;
  toolName: string;
  qualifiedName: string;
  argumentsJson: string;
}

export interface CardItem {
  icon?: string | null;
  title: string;
  detail?: string | null;
  value?: string | null;
  image?: string | null;
  fields?: Array<Pair>;
  group?: string | null;
  kind?: CardItemKind;
}

export type CardItemKind = "PLACE" | "TRANSPORT" | "STAY" | "WEATHER";

export type CardLayout = "LIST" | "GRID" | "COMPARE" | "STRIP" | "TRIP";

export interface CardProgress {
  label: string;
  value: number;
  max: number;
  text?: string | null;
}

export interface CheckAppParams {
  id: string;
  record?: boolean;
}

export type CheckFixKind = "MODELS" | "IMAGE_MODEL" | "VOICE" | "RECORD_PERMISSION" | "OVERLAY" | "ACCESSIBILITY" | "NOTIFICATIONS" | "WAKE_ON" | "OVERLAY_START";

export type CheckGroup = "MODELS" | "VOICE" | "PERMISSIONS";

export type CheckId = "CHAT" | "DEEPSEEK" | "IMAGE" | "TOOLS" | "ASR" | "JEV" | "TTS" | "WAKE" | "MIC" | "COMPANION" | "REALTIME" | "RECORD_PERMISSION" | "OVERLAY" | "ACCESSIBILITY" | "NOTIFICATIONS";

export interface CheckLine {
  finding: Finding;
  failed?: boolean;
}

export type CheckStatus = "RUNNING" | "OK" | "WARN" | "FAIL" | "OFF";

export interface CheckView {
  id: CheckId;
  group: CheckGroup;
  status?: CheckStatus | null;
  finding?: Finding | null;
  fix?: CheckFixKind | null;
}

export interface ChecksSection {
  checks?: ChecksView;
}

export interface ChecksView {
  checks?: Array<CheckView>;
  running?: boolean;
  checkedAtEpochMillis?: number | null;
}

export interface ChoiceOption {
  label: string;
  call: BoundCall;
}

export interface ClientCapabilities {
  approvals?: boolean;
  questions?: boolean;
  trajectory?: boolean;
  presentation?: boolean;
  state?: boolean;
}

export interface ClientInfo {
  name: string;
  version?: string;
  title?: string;
}

export interface CompanionConfig {
  enabled?: boolean;
  mode?: CompanionMode;
  threshold?: number;
  service?: CompanionService;
  persona?: string;
  endpoints?: Partial<Record<CompanionService, CompanionEndpoint>>;
}

export interface CompanionDecisionView {
  atEpochMillis: number;
  words: string;
  toCompanion: boolean;
  reason: Finding;
}

export interface CompanionDecisionsResult {
  decisions: Array<CompanionDecisionView>;
}

export interface CompanionEndpoint {
  baseUrl: string;
  model: string;
  extraBody: string;
}

export type CompanionMode = "OBSERVE" | "ROUTE";

export interface CompanionSaveParams {
  config: CompanionConfig;
  key?: string;
}

export interface CompanionSaveResult {
  view: CompanionView;
  error?: Finding | null;
}

export type CompanionService = "QWEN" | "DOUBAO" | "HUNYUAN" | "CUSTOM";

export interface CompanionTryResult {
  reply?: string | null;
  elapsedMillis?: number;
  failure?: Finding | null;
}

export interface CompanionView {
  config?: CompanionConfig;
  ownKeys?: Array<CompanionService>;
  usableKeys?: Array<CompanionService>;
  notReady?: Finding | null;
}

export type ConfirmationMode = "ASK_EVERY_TIME" | "ALLOW_CONFIRMED_DOMAINS" | "ALLOW_ALL_SESSION" | "DENY_ALL";

export type ConfirmationStatus = "PENDING" | "APPROVED" | "DENIED" | "AUTO_APPROVED" | "AUTO_DENIED" | "CONSUMED" | "EXPIRED" | "FAILED";

export interface ConnectAppParams {
  id: string;
  credential?: string;
  sandbox?: boolean;
}

export interface ConnectorCatalog {
  connectors?: Array<ConnectorView>;
  statuses?: Record<string, ConnectorStatus>;
}

export interface ConnectorPage {
  status?: ConnectorStatus;
  account?: string | null;
  accountPage?: string;
  relayHost?: string;
  events?: Array<string>;
  sandbox?: boolean;
}

export interface ConnectorSignInParams {
  id: string;
  name: string;
  password: string;
  register: boolean;
}

export type ConnectorStatus =
  | ({ type: "connected" } & ConnectorStatusConnected)
  | { type: "disconnected" }
  | ({ type: "pairing" } & ConnectorStatusPairing)
  | { type: "paused" };

export interface ConnectorStatusConnected {
  verified: boolean;
}

export type ConnectorStatusDisconnected = Record<string, never>;

export interface ConnectorStatusPairing {
  code: string;
  connectUrl: string;
  expiresAtMillis: number;
}

export type ConnectorStatusPaused = Record<string, never>;

export interface ConnectorStatuses {
  statuses?: Record<string, ConnectorStatus>;
}

export interface ConnectorTools {
  tools?: Array<string>;
}

export interface ConnectorView {
  id: string;
  name: string;
  title: string;
  summary: string;
  category: string;
  mark: string;
  color: number;
  app?: McpAppView | null;
}

export interface ContextPanelState {
  visible: boolean;
  todo: Array<TodoEntry>;
  lessons: Array<LessonEntry>;
  skills: Array<SkillEntry>;
}

export interface ContextUsage {
  inputTokens?: number | null;
  windowTokens?: number | null;
  awaitingAfterCompaction?: boolean;
}

export type Control =
  | ({ type: "choice" } & ControlChoice)
  | ({ type: "slider" } & ControlSlider)
  | ({ type: "switch" } & ControlSwitch);

export interface ControlChoice {
  label: string;
  options: Array<ChoiceOption>;
  value: Array<number>;
  multiple: boolean;
}

export interface ControlSlider {
  label: string;
  call: BoundCall;
  param: string;
  min: number;
  max: number;
  step: number;
  value: number;
  unit: string;
  integer: boolean;
}

export interface ControlSwitch {
  label: string;
  on: BoundCall;
  off: BoundCall;
  value: boolean;
}

export type ConversationItem =
  | ({ type: "approval" } & ConversationItemApproval)
  | ({ type: "message" } & ConversationItemMessage)
  | ({ type: "runtimePhase" } & ConversationItemRuntimePhase)
  | ({ type: "toolCall" } & ConversationItemToolCall)
  | ({ type: "userMessage" } & ConversationItemUserMessage);

export interface ConversationItemApproval {
  id: number;
  pendingId: string;
  domain?: string;
  mode: ConfirmationMode;
  status?: ConfirmationStatus;
  createdAt: number;
  resolvedAt?: number | null;
}

export interface ConversationItemMessage {
  id: number;
  role: TranscriptRole;
  content: string;
  final?: boolean;
}

export interface ConversationItemRuntimePhase {
  id: number;
  title: string;
  body: string;
  markdown?: boolean;
}

export interface ConversationItemToolCall {
  id: number;
  toolCallId?: string;
  toolName: string;
  argumentsJson?: string | null;
  resultJson?: string | null;
  status: ToolCallStatus;
  startedAt: number;
  finishedAt?: number | null;
}

export interface ConversationItemUserMessage {
  id: number;
  label: string;
  preview: string;
  fullText: string;
}

export interface CurrentRequest {
  requestId: string;
  text: string;
}

export interface DataClearParams {
  alsoMemories?: boolean;
}

export interface DataClearResult {
  cleared: boolean;
}

export interface DeepSeekBalanceParams {
  apiKey?: string;
  connection?: ProviderConnection | null;
  url?: string | null;
}

export interface DeepSeekBalanceResult {
  balance?: BalanceView | null;
  failure?: Finding | null;
}

export interface DeepSeekCheckView {
  passed: boolean;
  note: Finding;
  url?: string | null;
}

export interface DeepSeekProbeView {
  checked?: boolean;
  current?: boolean;
  hasKey?: boolean;
  balance?: DeepSeekCheckView | null;
  tools?: DeepSeekCheckView | null;
  passthrough?: DeepSeekCheckView | null;
}

export type DelegationStage = "DELIVERED" | "STALLED" | "STARTED" | "WORKING" | "REPORTING" | "DONE" | "ASKING" | "FAILED" | "CANCELLED" | "LOST";

export interface DelegationStep {
  title: string;
  status: string;
}

export interface DelegationView {
  profileId: string;
  agentLabel: string;
  taskId: string;
  request: string;
  startedAtEpochMillis: number;
  stage: DelegationStage;
  statusText?: string;
  steps?: Array<DelegationStep>;
  updatedAtEpochMillis?: number;
  outcome?: string | null;
  said?: string;
  ended?: Finding | null;
}

export interface DoneResult {
  done: boolean;
}

export interface DoubaoTtsView {
  appId?: string;
  resourceId?: string;
  voice?: string;
  speechRate?: number;
  hasKey?: boolean;
}

export type DownloadFailure = "NO_SOURCE" | "DOWNLOAD" | "CHECKSUM" | "RENAME";

export type DownloadPhase = "DOWNLOADING" | "VERIFYING" | "DONE" | "FAILED";

export interface DragParams {
  fromX: number;
  fromY: number;
  toX: number;
  toY: number;
  durationMs: number;
}

export type Empty = Record<string, never>;

export interface EnabledParams {
  id: string;
  enabled: boolean;
}

export type EventLogDurability = "DURABLE" | "VOLATILE" | "UNKNOWN";

export type EventLogHealthStatus = "COMPLETE" | "DEGRADED" | "UNAVAILABLE" | "VOLATILE" | "UNKNOWN";

export interface FileEntryView {
  name: string;
  directory: boolean;
  sizeBytes?: number | null;
}

export interface FileListResult {
  entries?: Array<FileEntryView>;
  missing?: boolean;
  error?: string | null;
}

export interface FileMountsResult {
  targets?: Array<string>;
}

export interface FileReadParams {
  path: string;
  maxBytes?: number;
}

export interface FileReadResult {
  text?: string | null;
}

export interface Finding {
  code: string;
  args?: Array<string>;
  more?: Array<Finding>;
  balance?: BalanceView | null;
  count?: number | null;
}

export interface FlagParams {
  on: boolean;
}

export interface GeneratedImageResult {
  reference: string;
  width: number;
  height: number;
  bytes: number;
  elapsedMillis: number;
  revisedPrompt?: string | null;
}

export interface GeneratedPicture {
  reference?: string | null;
  prompt: string;
}

export interface HandOverNoticeView {
  handOver: SegmentHandOver;
  text: string;
  atEpochMillis: number;
  fellBack?: boolean;
  ack?: string | null;
}

export interface IdParams {
  id: string;
}

export interface IdsParams {
  ids: Array<string>;
}

export interface ImageModelConfig {
  enabled?: boolean;
  baseUrl?: string;
  model?: string;
  size?: string;
  timeoutMillis?: number;
  hasApiKey?: boolean;
}

export type ImageModelProblem = "BASE_URL_SCHEME" | "BASE_URL_PARTS" | "MODEL_WHITESPACE" | "NO_MODEL" | "SIZE" | "TIMEOUT" | "NO_KEY" | "KEY_WHITESPACE" | "KEY_NOT_REMOVED" | "NOT_SAVED" | "KEY_NOT_SAVED";

export interface ImageModelSaveParams {
  config: ImageModelConfig;
  replacementKey?: string;
  clearKey?: boolean;
}

export interface ImageModelSaveResult {
  config: ImageModelConfig;
  problem?: ImageModelProblem | null;
}

export interface ImageTryResult {
  image?: GeneratedImageResult | null;
  failure?: string | null;
  elapsedMillis?: number;
}

export type InferenceServerPhase = "STOPPED" | "STARTING" | "READY" | "FAILED";

export interface InferenceServerView {
  phase?: InferenceServerPhase;
  modelId?: string;
  device?: string;
  baseUrl?: string;
  reason?: string;
}

export interface InitializeParams {
  clientInfo: ClientInfo;
  capabilities?: ClientCapabilities;
}

export interface InitializeResult {
  protocolVersion: number;
  serverInfo: ServerInfo;
  snapshot: ServerSnapshot;
}

export interface ItemChanged {
  threadId: string;
  item: ConversationItem;
}

export interface ItemDelta {
  threadId: string;
  itemId: number;
  delta: string;
}

export interface ItemRemoved {
  threadId: string;
  itemId: number;
}

export interface JevConnectionDraft {
  endpoint: string;
  model: string;
  timeoutMillis: number;
  key?: string;
}

export type JevSearchMode = "FLAT" | "TWO_STAGE" | "TWO_STAGE_SUBDOMAINS";

export interface JevSettings {
  enabled?: boolean;
  endpoint?: string;
  model?: string;
  timeoutMillis?: number;
  minScore?: number;
  maxTools?: number;
  hasApiKey?: boolean;
  mode?: JevSearchMode;
  domainMinScore?: number;
}

export type Kind = "MESSAGE" | "STEER" | "ANSWER";

export interface LessonEntry {
  scene: string;
  content: string;
  confidence: number;
  hitCount: number;
}

export interface MascotParams {
  name: string;
}

export interface McpAppView {
  url: string;
  sandboxUrl?: string | null;
  credentialLabel?: string;
  needsCredential?: boolean;
  portal?: string;
  steps?: Array<string>;
  abilities?: Array<string>;
  example?: string;
  note?: string | null;
}

export interface McpProviderState {
  id: string;
  label: string;
  enabled: boolean;
  server?: McpServer | null;
}

export interface McpSecretsView {
  token?: boolean;
  headers?: number;
  query?: boolean;
}

export interface McpServer {
  id: string;
  label: string;
  kind: McpServerKind;
  enabled: boolean;
  url?: string;
}

export interface McpServerDraft {
  id?: string | null;
  label: string;
  kind: McpServerKind;
  url?: string;
  token?: string;
  headers?: string;
  clearSecrets?: boolean;
  query?: Record<string, string>;
  preferredId?: string | null;
}

export type McpServerKind = "HTTP";

export interface McpStatusView {
  titles?: Record<string, string>;
  secrets?: Record<string, McpSecretsView>;
  mediaProviderId?: string;
  mediaAccess?: boolean;
}

export interface McpToolPreview {
  name: string;
  title: string;
  description: string;
}

export interface McpToolsView {
  tools?: Array<McpToolPreview> | null;
  unavailable?: Finding | null;
}

export type MemoryCategory = "IDENTITY" | "PLACES" | "HOME" | "MEDIA" | "TRAVEL" | "PEOPLE" | "OTHER";

export interface MemoryLessons {
  activeCount?: number;
  pendingCount?: number;
  active?: Array<LessonEntry>;
  pending?: Array<LessonEntry>;
}

export type MemorySource = "SAID" | "TYPED";

export interface MemoryView {
  memories?: Array<UserMemory>;
  enabled?: boolean;
}

export interface MemoryWriteParams {
  category: MemoryCategory;
  content: string;
  id?: string | null;
}

export interface MemoryWriteResult {
  saved: boolean;
  created?: boolean;
  refusal?: Finding | null;
}

export type ModelCallPhase = "REQUEST" | "USAGE" | "FINISHED";

export interface ModelDownloadView {
  phase: DownloadPhase;
  bytes?: number;
  total?: number;
  source?: string;
  failure?: DownloadFailure | null;
  detail?: string;
}

export interface ModelListResult {
  models: Array<string>;
}

export interface ModelPrice {
  input: number;
  output: number;
  cacheRead?: number | null;
  cacheWrite?: number | null;
  source?: string;
  pricedAs?: string | null;
  manual?: boolean;
}

export interface ModelPriceParams {
  model: string;
  price?: ModelPrice | null;
}

export type ModelProvider = "OPENAI" | "ANTHROPIC";

export interface ModelSelectParams {
  provider?: ModelProvider | null;
  profileId?: string | null;
  manualProfileEnabled?: boolean | null;
  model?: string | null;
  variant?: string | null;
}

export interface ModelSettingsParams {
  profile: ProviderEditState;
  models: Array<string>;
}

export interface ModelSettingsResult {
  models: Array<ModelSettingsView>;
}

export interface ModelSettingsSaveParams {
  profile: ProviderEditState;
  models: Array<ModelSettingsView>;
}

export interface ModelSettingsView {
  model: string;
  windowOverride?: number | null;
  defaultWindow?: number;
  imageInput?: boolean;
}

export interface ModelUsage {
  requests?: number;
  inputTokens?: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
  outputTokens?: number;
}

export interface NameEnabledParams {
  name: string;
  enabled: boolean;
}

export interface Offer {
  sessionId: string;
  turnId: string;
  options: Array<OfferOption>;
  createdAtMillis: number;
  controls?: Array<Control>;
  cards?: Array<WidgetCard>;
}

export interface OfferOption {
  label: string;
  call?: BoundCall | null;
  icon?: string | null;
}

export interface OnDeviceModelView {
  id: string;
  label: string;
  publisher?: string;
  quantization?: string;
  sizeBytes?: number;
  license?: string;
  note?: string;
  downloaded?: boolean;
  onDiskBytes?: number;
  download?: ModelDownloadView | null;
}

export interface OnDeviceSection {
  onDevice?: OnDeviceView;
}

export interface OnDeviceView {
  models?: Array<OnDeviceModelView>;
  server?: InferenceServerView;
}

export interface OnboardingView {
  needed?: boolean;
  doubaoKeySaved?: boolean;
  notificationAccess?: boolean;
  accessibilityEnabled?: boolean;
}

export type OverlayCommand = "show" | "hide";

export interface PageReadParams {
  reference: string;
}

export interface PageReadResult {
  html?: string | null;
}

export interface Pair {
  first: string;
  second: string;
}

export interface PathParams {
  path: string;
}

export interface Plan {
  id: string;
  title: string;
  message: string;
  instruction?: string | null;
  trigger: PlanTrigger;
  enabled: boolean;
  createdAtMillis: number;
  lastFiredAtMillis?: number | null;
  lastDueAtMillis?: number | null;
  firedCount?: number;
  end?: PlanEnd | null;
  said?: string | null;
  actions?: Array<PlanAction>;
}

export interface PlanAction {
  server: string;
  tool: string;
  arguments: Record<string, unknown>;
  label: string;
}

export interface PlanDraft {
  title: string;
  message?: string;
  instruction?: string | null;
  trigger: PlanTrigger;
}

export type PlanEnd = "DONE" | "MISSED";

export interface PlanFiring {
  planId: string;
  title: string;
  atMillis: number;
  note: Finding;
}

export type PlanKind = "auto" | "task" | "reminder";

export interface PlanNoticeEntry {
  planId: string;
  kind: PlanKind;
  title: string;
  handed?: string | null;
  said?: string | null;
  actions?: Array<string>;
  outcome?: Finding | null;
}

export interface PlanRanParams {
  planId: string;
  outcome: Finding;
}

export type PlanTrigger =
  | ({ type: "once" } & PlanTriggerOnce)
  | ({ type: "repeat" } & PlanTriggerRepeat)
  | ({ type: "watch" } & PlanTriggerWatch);

export interface PlanTriggerOnce {
  atMillis: number;
}

export interface PlanTriggerRepeat {
  minuteOfDay: number;
  days: Array<number>;
}

export interface PlanTriggerWatch {
  watch: PlanWatch;
  holdSeconds: number;
  repeat: boolean;
}

export interface PlanWatch {
  server: string;
  uri: string;
  field: string;
  op: WatchOp;
  value?: unknown | null;
  text: string;
  where?: Record<string, unknown>;
  mode?: WatchMode;
  margin?: number;
  pollMillis?: number;
  tool?: string | null;
  arguments?: Record<string, unknown>;
}

export interface PointParams {
  x: number;
  y: number;
  press: boolean;
}

export interface Preferences {
  sessionPolicy?: SessionPolicy;
  basicTools?: Array<BasicToolView>;
  androidUse?: AndroidUseView;
  runtimeSkills?: Array<RuntimeSkillView>;
  subagents?: SubagentSettingsView;
  subagentKinds?: Array<SubagentKindView>;
  webLookup?: boolean;
}

export interface PresentCardParams {
  command: OverlayCommand;
}

export interface PresentResult {
  refusal?: string | null;
  noResting?: boolean;
}

export interface PresentStartParams {
  open: boolean;
}

export interface PresentWakeParams {
  origin: string;
}

export interface PresentationState {
  ready?: boolean;
  readingUntilEpochMillis?: number;
  inFront?: boolean;
}

export interface PriceBook {
  prices?: Record<string, ModelPrice>;
  usdToCny?: number;
  syncedAtEpochMillis?: number | null;
}

export interface PriceSyncResult {
  matched?: number;
  total?: number;
  failure?: string | null;
}

export interface ProviderConnection {
  profileId: string;
  label?: string;
  provider?: ModelProvider;
  endpoint?: ProviderEndpoint;
  apiKey?: string;
  model?: string;
  baseUrl?: string;
}

export interface ProviderCreateParams {
  label: string;
  provider: ModelProvider;
  endpoint: ProviderEndpoint;
  apiKey: string;
  model: string;
  baseUrl: string;
}

export interface ProviderCreateResult {
  profileId: string;
}

export interface ProviderEditState {
  model?: string;
  variant?: string;
  baseUrl?: string;
  profileId?: string;
  profileLabel?: string;
  provider?: ModelProvider;
  endpoint?: ProviderEndpoint;
  manualProfileEnabled?: boolean;
  profileOptions?: Array<ProviderProfileOption>;
  modelOptions?: Array<string>;
  variantOptions?: Array<string>;
  defaultVariant?: string | null;
  hasApiKey?: boolean;
}

export type ProviderEndpoint = "CHAT_COMPLETIONS" | "RESPONSES" | "MESSAGES";

export interface ProviderFetchParams {
  profileId: string;
  provider: ModelProvider;
  endpoint: ProviderEndpoint;
  apiKey: string;
  model: string;
  baseUrl: string;
}

export interface ProviderModelsParams {
  editor: ProviderEditState;
  models: Array<string>;
}

export interface ProviderProfileOption {
  id: string;
  label: string;
  provider: ModelProvider;
  endpoint: ProviderEndpoint;
  model: string;
  selected: boolean;
  canDelete: boolean;
}

export interface ProviderSaveParams {
  provider: ModelProvider;
  apiKey?: string;
  model: string;
  baseUrl: string;
  endpoint: ProviderEndpoint;
}

export interface QuestionAnswerParams {
  question: UserQuestion;
  text: string;
}

export interface RateParams {
  usdToCny: number;
}

export interface RealtimeExchangeView {
  heard: string;
  reply?: string;
}

export interface RealtimeSettings {
  enabled?: boolean;
  voice?: string;
  persona?: string;
  bargeIn?: boolean;
  personaPreset?: string;
  lateSpeech?: boolean;
}

export interface RealtimeView {
  settings?: RealtimeSettings;
  effectivePersona?: string;
  voices?: Array<SettingsOption>;
  personas?: Array<SettingsOption>;
  hasKey?: boolean;
}

export type RunActivity = "idle" | "starting" | "working" | "thinking" | "runningTool" | "waitingForApproval" | "blocked" | "failed" | "stopped";

export type RunOutcome = "completed" | "partial" | "replayFailed" | "failed" | "stopped";

export interface RunResult {
  outcome: RunOutcome;
  durationMs: number;
}

export interface RunSettingsParams {
  confirmationMode?: ConfirmationMode | null;
  simulatedBackend?: boolean | null;
  toggleStream?: boolean;
}

export interface RuntimeDemoState {
  runId?: string;
  status?: string;
  thinkingPreview?: string;
  assistantPreview?: string;
  pendingConfirmationIds?: Array<string>;
  steeringPreview?: string;
  execOutputPreview?: string;
  workspaceId?: string;
  retryCount?: number;
  runningTools?: Array<string>;
  finishedTools?: Array<string>;
  changedFiles?: Array<RuntimeFileChange>;
  tokenUsage?: RuntimeTokenUsage;
  finalStatus?: string | null;
  errorCode?: string;
}

export interface RuntimeFileChange {
  path: string;
  addedLines: number;
  removedLines: number;
  diffTimedOut: boolean;
}

export interface RuntimeSkillView {
  id: string;
  label: string;
  description: string;
  enabled: boolean;
  custom?: boolean;
}

export interface RuntimeTokenUsage {
  inputTokens?: number;
  outputTokens?: number;
  reasoningTokens?: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
}

export interface SaveResult {
  error?: Finding | null;
}

export type SegmentHandOver = "NEW_TURN" | "APPEND" | "REPLACE";

export type SegmentLabel = "AGENT" | "SIDE" | "UNCERTAIN";

export interface ServerInfo {
  name: string;
  version?: string;
  features?: Array<string>;
}

export interface ServerSnapshot {
  threads?: Array<SessionSummary>;
  activeThreadId?: string;
  thread?: ThreadSnapshot;
  sections?: Array<StateSection>;
}

export interface SessionBoardState {
  activeSession?: SessionSummary | null;
  recentTurns: Array<SessionTurn>;
  lastAction?: SessionToolCall | null;
}

export interface SessionPolicy {
  everyWake?: boolean;
  idleMinutes?: number;
  maxSessions?: number;
}

export interface SessionSummary {
  sessionId: string;
  title: string;
  updatedAtEpochMillis: number;
  turnCount: number;
  compactedSummary: string;
  lifecycleStatus?: string;
  completionOutcome?: string;
  logHealth?: string;
}

export interface SessionToolCall {
  sequence: number;
  toolName: string;
  argumentsPreview: string;
  resultPreview: string;
  status: string;
  toolCallId?: string;
}

export interface SessionTurn {
  turnId: string;
  requestId: string;
  runId: string;
  sessionId: string;
  scene: string;
  userText: string;
  startedAtEpochMillis: number;
  completedAtEpochMillis: number;
  finalStatus: string;
  finalResponse: string;
  tools: Array<SessionToolCall>;
  completionOutcome?: string;
  replayStatus?: string;
  replaySummary?: string;
  usage?: TokenUsageState;
}

export interface SettingsOption {
  id: string;
  label: string;
  summary?: string;
}

export interface SkillCheckView {
  name?: string | null;
  description?: string | null;
  errors?: Array<Finding>;
  warnings?: Array<Finding>;
  normalized?: string;
}

export interface SkillEntry {
  name: string;
  description: string;
}

export interface SkillTextParams {
  text: string;
  editing?: string | null;
}

export interface SpeechSection {
  level?: number | null;
}

export interface StateChanged {
  section: StateSection;
}

export type StateSection =
  | ({ type: "a2aClient" } & StateSectionA2aClient)
  | ({ type: "a2a" } & StateSectionA2a)
  | ({ type: "artifact" } & StateSectionArtifact)
  | ({ type: "context" } & StateSectionContext)
  | ({ type: "delegations" } & StateSectionDelegations)
  | ({ type: "demo" } & StateSectionDemo)
  | ({ type: "extension" } & StateSectionExtension)
  | ({ type: "mcp" } & StateSectionMcp)
  | ({ type: "memory" } & StateSectionMemory)
  | ({ type: "model" } & StateSectionModel)
  | ({ type: "offers" } & StateSectionOffers)
  | ({ type: "plans" } & StateSectionPlans)
  | ({ type: "preferences" } & StateSectionPreferences)
  | ({ type: "run" } & StateSectionRun)
  | ({ type: "subagents" } & StateSectionSubagents)
  | ({ type: "trajectory" } & StateSectionTrajectory)
  | ({ type: "usage" } & StateSectionUsage)
  | ({ type: "workspace" } & StateSectionWorkspace);

export interface StateSectionA2a {
  a2aClients?: A2aModuleState;
}

export interface StateSectionA2aClient {
  a2aClient?: A2aClientState;
}

export interface StateSectionArtifact {
  artifactPreview?: ArtifactPreview | null;
}

export interface StateSectionContext {
  contextPanel?: ContextPanelState;
  board?: SessionBoardState;
  tokenUsage?: TokenUsageState;
  contextUsage?: ContextUsage;
}

export interface StateSectionDelegations {
  delegations?: Array<DelegationView>;
}

export interface StateSectionDemo {
  runtimeDemo?: RuntimeDemoState;
}

export interface StateSectionExtension {
  name: string;
  data?: Record<string, unknown>;
}

export interface StateSectionMcp {
  mcpProviders?: Array<McpProviderState>;
}

export interface StateSectionMemory {
  memory?: MemoryView;
}

export interface StateSectionModel {
  providerLabel?: string;
  stream?: boolean;
  simulated?: boolean;
  configSource?: string;
  confirmationMode?: ConfirmationMode;
  configEditor?: ProviderEditState;
}

export interface StateSectionOffers {
  offers?: Record<string, Offer>;
  pictures?: Record<string, GeneratedPicture>;
}

export interface StateSectionPlans {
  plans?: Array<Plan>;
  planHistory?: Array<PlanFiring>;
  planFeeds?: Record<string, SubscriptionState>;
}

export interface StateSectionPreferences {
  preferences?: Preferences;
}

export interface StateSectionRun {
  activity?: RunActivity;
  tool?: string;
  runtimeStatus?: string;
  isRunning?: boolean;
  currentRequest?: CurrentRequest | null;
  runStartedAtEpochMillis?: number | null;
  lastRun?: RunResult | null;
  activeSessionCount?: number;
  maxActiveSessions?: number;
  runningThreads?: Record<string, string>;
}

export interface StateSectionSubagents {
  board?: Record<string, Array<SubagentLane>>;
  deliveries?: Record<string, number>;
}

export interface StateSectionTrajectory {
  trajectory?: TrajectorySnapshot;
}

export interface StateSectionUsage {
  revision?: number;
}

export interface StateSectionWorkspace {
  workspaceSelection?: WorkspaceSelectionState;
}

export interface SubagentKindView {
  id: string;
  label: string;
  description: string;
  maxLookups: number;
  searchApi?: boolean | null;
  enabled?: boolean;
  tools?: Array<SubagentToolKind>;
  requiresWebLookup?: boolean;
  defaultTools?: Array<SubagentToolKind>;
  defaultLookups?: number;
}

export interface SubagentLane {
  id: string;
  label: string;
  state: SubagentRunState;
  startedAtEpochMillis: number;
  lastActivityEpochMillis?: number;
  finishedAtEpochMillis?: number;
  toolCalls?: number;
  latest?: string;
  result?: string;
  error?: string;
  parentSessionId?: string;
  sessionId?: string;
  task?: string;
  modelCalls?: number;
  inputTokens?: number;
  cachedTokens?: number;
  outputTokens?: number;
  agentType?: string;
}

export type SubagentRunState = "RUNNING" | "STALLED" | "COMPLETED" | "FAILED" | "CANCELLED" | "TIMED_OUT";

export interface SubagentSettingsView {
  allowDeviceControl?: boolean;
  model?: string;
  enabled?: boolean;
  searchApi?: boolean;
}

export type SubagentToolKind = "SEARCH" | "FETCH" | "READ" | "WRITE" | "TODO" | "DEVICE_QUERY" | "DEVICE_CONTROL" | "SERVICES";

export interface SubagentToolsParams {
  id: string;
  tools: Array<SubagentToolKind>;
  maxLookups: number;
}

export type SubscriptionState = "OFFLINE" | "PENDING" | "LIVE" | "REFUSED" | "NO_PUSH";

export interface TakenResult {
  taken: boolean;
}

export interface TextParams {
  text: string;
}

export interface TextResult {
  text: string;
}

export interface ThreadIdParams {
  threadId: string;
}

export interface ThreadListChanged {
  threads: Array<SessionSummary>;
  activeThreadId: string;
}

export interface ThreadListResult {
  threads: Array<SessionSummary>;
  activeThreadId: string;
}

export interface ThreadSnapshot {
  threadId: string;
  items: Array<ConversationItem>;
}

export interface ThreadStartResult {
  threadId: string;
}

export interface TodoEntry {
  content: string;
  status: string;
}

export interface TokenUsageState {
  requestCount?: number;
  inputTokens?: number;
  outputTokens?: number;
  reasoningTokens?: number;
  totalTokens?: number;
  cachedInputTokens?: number;
  cacheWriteTokens?: number;
  cacheHitTokens?: number;
  cacheMissTokens?: number;
}

export type ToolCallStatus = "RUNNING" | "DONE" | "FAILED" | "UNKNOWN";

export interface ToolSearchSection {
  settings?: JevSettings;
}

export interface TrajectoryCorrelation {
  toolCallId?: string | null;
  modelCallId?: string | null;
  lifecyclePhase?: string | null;
  localOrdinal?: number | null;
}

export interface TrajectoryHealth {
  eventLogStatus?: EventLogHealthStatus;
  durability?: EventLogDurability;
  attemptedCount?: number;
  appendedCount?: number;
  failedCount?: number;
  lastAcceptedSequence?: number | null;
  lastPersistedSequence?: number | null;
  hasSequenceGap?: boolean;
  hasLiveOnlyRecords?: boolean;
  richAvailability?: TrajectoryRichAvailability;
  safeReasonCode?: string | null;
}

export type TrajectoryKind = "RUN_START" | "INPUT" | "THINKING" | "MODEL_MESSAGE" | "MODEL_CALL" | "MODEL_TOOL" | "TOOL_START" | "TOOL_FINISH" | "USAGE" | "CONFIRMATION" | "RETRY" | "COMPACTION" | "NETWORK" | "SEARCH" | "EXEC_OUTPUT" | "WORKSPACE" | "STATUS" | "RESULT" | "RUN_END" | "GAP" | "LOAD_ERROR";

export type TrajectoryLane = "INPUT" | "MODEL" | "TOOLS" | "SYSTEM";

export interface TrajectoryModelCall {
  phase: ModelCallPhase;
  modelRef?: string;
  inputTokens?: number;
  source?: string;
}

export interface TrajectoryQuestion {
  questionId: string;
  kind: string;
  text: string;
  options?: Array<string>;
  state: string;
}

export interface TrajectoryRaw {
  text?: string | null;
}

export interface TrajectoryRawParams {
  sessionId: string;
  eventId: string;
}

export interface TrajectoryRecord {
  id: string;
  sessionId: string;
  runId: string;
  requestId?: string | null;
  eventId?: string | null;
  sequence?: number | null;
  occurredAtEpochMillis?: number | null;
  timeQuality?: TrajectoryTimeQuality;
  kind: TrajectoryKind;
  lane: TrajectoryLane;
  status?: string | null;
  title: string;
  summary?: string;
  correlation?: TrajectoryCorrelation;
  safeMetadata?: Record<string, string>;
  contentHashes?: Record<string, string>;
  sourceEventSequences?: Array<number>;
  sources?: Array<TrajectorySource>;
  rich?: TrajectoryRichDetails | null;
  richAvailability?: TrajectoryRichAvailability;
  isLive?: boolean;
}

export type TrajectoryRichAvailability = "AVAILABLE" | "PARTIAL" | "REDACTED" | "HASH_ONLY" | "UNAVAILABLE";

export interface TrajectoryRichDetails {
  inputPreview?: string | null;
  payloadPreview?: string | null;
  resultPreview?: string | null;
  schemaPreview?: string | null;
  requestText?: string | null;
  thinkingText?: string | null;
  outputText?: string | null;
  usageText?: string | null;
  timing?: Record<string, number>;
  question?: TrajectoryQuestion | null;
  modelCall?: TrajectoryModelCall | null;
}

export interface TrajectoryRunGroup {
  sessionId: string;
  runId: string;
  requestId?: string | null;
  startedAtEpochMillis?: number | null;
  completedAtEpochMillis?: number | null;
  lifecycleStatus?: string;
  completionOutcome?: string | null;
  isActive?: boolean;
  recordsLoaded?: boolean;
  loadFailed?: boolean;
  records?: Array<TrajectoryRecord>;
  health?: TrajectoryHealth;
}

export interface TrajectorySnapshot {
  sessionId: string;
  runGroups?: Array<TrajectoryRunGroup>;
  visibleRunCount?: number;
  totalKnownRunCount?: number;
  hasOlderRuns?: boolean;
  loadingRunIds?: Array<string>;
  refreshing?: boolean;
  recordAliases?: Record<string, string>;
  revision?: number;
}

export type TrajectorySource = "EVENT_LOG" | "SESSION_TURN" | "RUN_EVIDENCE" | "SYNTHETIC_BOUNDARY";

export type TrajectoryTimeQuality = "PERSISTED_RECORDED" | "LIVE_ARRIVAL" | "SUMMARY_RANGE" | "UNKNOWN";

export type TranscriptRole = "ASSISTANT" | "THINKING" | "RUNTIME" | "STATUS" | "ERROR" | "NOTICE";

export type TtsChoice = "ENABLED" | "VOICE" | "STREAM" | "PROVIDER" | "DOUBAO_VOICE" | "DOUBAO_SPEECH_RATE";

export interface TtsChoiceParams {
  choice: TtsChoice;
  value: string;
}

export type TtsProvider = "MIMO" | "DOUBAO" | "SYSTEM";

export interface TtsSettings {
  enabled?: boolean;
  provider?: TtsProvider;
  baseUrl?: string;
  model?: string;
  voice?: string;
  style?: string;
  stream?: boolean;
  hasApiKey?: boolean;
  ready?: boolean;
  status?: Finding | null;
  doubao?: DoubaoTtsView;
}

export interface TurnCompleted {
  threadId: string;
  requestId: string;
  result?: RunResult | null;
}

export interface TurnInterruptParams {
  threadId?: string | null;
}

export interface TurnStartParams {
  text: string;
  threadId?: string | null;
  focus?: boolean;
}

export interface TurnStarted {
  threadId: string;
  requestId: string;
  startedAtEpochMillis?: number | null;
}

export interface UriParams {
  uri: string;
}

export interface UsageData {
  days?: Record<string, Record<string, ModelUsage>>;
  prices?: PriceBook;
}

export interface UserMemory {
  id: string;
  category: MemoryCategory;
  content: string;
  source: MemorySource;
  said?: string | null;
  createdAtMillis: number;
  updatedAtMillis: number;
}

export interface UserQuestion {
  id: string;
  runId: string;
  sessionId: string;
  title: string;
  options: Array<string>;
  state: string;
}

export interface VoiceEffectsView {
  echoCanceller: boolean;
  echoGate?: boolean;
}

export type VoiceEngine = "LOCAL" | "DASHSCOPE";

export interface VoiceJudgmentView {
  turnComplete: number;
  holdingFloor: number;
  addressedToAgent: number;
  takingFloor?: number | null;
  replacesTask?: number | null;
}

export type VoiceKey = "DASHSCOPE" | "JEV" | "MIMO" | "DOUBAO";

export interface VoiceKeyParams {
  key: VoiceKey;
}

export interface VoiceSection {
  voice?: VoiceView;
}

export interface VoiceSegmentView {
  id: number;
  text: string;
  label: SegmentLabel;
  reason: string;
  endedAtEpochMillis: number;
  submitted?: boolean;
  handOver?: SegmentHandOver | null;
  dismissed?: boolean;
  judgment?: VoiceJudgmentView | null;
}

export interface VoiceSettings {
  engine?: VoiceEngine;
  localThreads?: number;
  dashscopeEndpoint?: string;
  dashscopeModel?: string;
  hasDashscopeKey?: boolean;
  autoSubmit?: boolean;
  historyMessages?: number;
  echoCancel?: boolean;
  bargeIn?: boolean;
  handOverChime?: boolean;
  jevTurns?: boolean;
  nearGate?: boolean;
  thresholds?: VoiceThresholds;
  wakeSensitivity?: string;
  nearGateBelowDb?: number;
  hotwords?: Array<string>;
}

export interface VoiceSettingsResult {
  view: VoiceSettingsView;
  error?: Finding | null;
}

export interface VoiceSettingsSaveParams {
  voice: VoiceSettings;
  asrKey?: string;
  jev?: JevConnectionDraft | null;
  tts?: TtsSettings | null;
  ttsKey?: string;
  doubaoKey?: string;
}

export interface VoiceSettingsView {
  voice?: VoiceSettings;
  jev?: JevSettings;
  tts?: TtsSettings;
  ttsKeyStored?: boolean;
  doubaoKeyStored?: boolean;
  localAsrProblem?: Finding | null;
  echoCancellerAvailable?: boolean;
  asrModels?: Array<SettingsOption>;
  mimoVoices?: Array<SettingsOption>;
  doubaoVoices?: Array<SettingsOption>;
  wakeLevels?: Array<WakeLevelView>;
  wakeIdleEndSeconds?: number;
}

export type VoiceStatus = "IDLE" | "STARTING" | "LISTENING" | "BUSY" | "ERROR";

export interface VoiceThresholds {
  eos?: number;
  eosFloor?: number;
  addresseeAgent?: number;
  addresseeSide?: number;
  silenceCapMillis?: number;
  bargeIn?: number;
}

export interface VoiceView {
  status?: VoiceStatus;
  engine?: VoiceEngine | null;
  asrModel?: string;
  liveText?: string;
  liveJudgment?: VoiceJudgmentView | null;
  micActive?: boolean;
  micLevels?: Array<number>;
  segments?: Array<VoiceSegmentView>;
  problem?: Finding | null;
  fullDuplex?: boolean;
  effects?: VoiceEffectsView | null;
  judgeCount?: number;
  judgeFailures?: number;
  lastJudgeLatencyMillis?: number | null;
  echoes?: number;
  interruptions?: number;
  agentWorking?: boolean;
  assistantSpeaking?: boolean;
  listening?: boolean;
  active?: boolean;
  holding?: boolean;
  finishingHold?: boolean;
  holdsToTalk?: boolean;
  handOver?: HandOverNoticeView | null;
  wakeSession?: boolean;
  notice?: Finding | null;
  speaking?: boolean;
  wakeWordEnabled?: boolean;
  wakeStatus?: Finding | null;
  wakeListening?: boolean;
  wakeMigrated?: boolean;
  realtime?: RealtimeExchangeView | null;
}

export interface VoiceWakeParams {
  source: WakeOrigin;
  onlyIfCanListen?: boolean;
}

export interface VoiceWakeResult {
  started: boolean;
  failure?: Finding | null;
}

export interface WakeLevelView {
  id: string;
  phrases: Array<string>;
}

export type WakeOrigin = "WORD" | "ENGLISH_WORD" | "LONG_PRESS" | "TAP" | "ADB" | "SURFACE";

export type WatchMode = "ENTER" | "EACH";

export type WatchOp = "ABOVE" | "BELOW" | "EQUALS" | "NOT_EQUALS" | "CONTAINS" | "CHANGES";

export interface WebLookupView {
  profileId?: string;
  profileLabel?: string;
  searchApi?: boolean;
  ownSearch?: boolean;
  fetchFallback?: boolean;
  lookupKey?: boolean;
}

export interface WidgetCard {
  title: string;
  icon?: string | null;
  value?: string | null;
  subtitle?: string | null;
  fields: Array<Pair>;
  items: Array<CardItem>;
  image?: string | null;
  layout?: CardLayout;
  progress?: Array<CardProgress>;
}

export interface WorkspaceSelectionState {
  label?: string;
  safEnabled?: boolean;
}

export interface ClientRequests {
  "initialize": { params: InitializeParams; result: InitializeResult };
  "thread/list": { params: Empty; result: ThreadListResult };
  "thread/start": { params: Empty; result: ThreadStartResult };
  "thread/select": { params: ThreadIdParams; result: ThreadSnapshot };
  "thread/delete": { params: ThreadIdParams; result: Empty };
  "thread/read": { params: ThreadIdParams; result: ThreadSnapshot };
  "thread/subscribe": { params: ThreadIdParams; result: ThreadSnapshot };
  "thread/unsubscribe": { params: ThreadIdParams; result: Empty };
  "turn/start": { params: TurnStartParams; result: Empty };
  "turn/steer": { params: TextParams; result: Empty };
  "turn/interrupt": { params: TurnInterruptParams; result: Empty };
  "approval/respond": { params: ApprovalRespondParams; result: Empty };
  "tools/setBasic": { params: NameEnabledParams; result: Empty };
  "tools/setAndroidUse": { params: FlagParams; result: Empty };
  "skills/set": { params: EnabledParams; result: Empty };
  "skills/text": { params: IdParams; result: TextResult };
  "skills/check": { params: SkillTextParams; result: SkillCheckView };
  "skills/save": { params: SkillTextParams; result: SaveResult };
  "skills/delete": { params: IdParams; result: Empty };
  "subagents/set": { params: SubagentSettingsView; result: Empty };
  "subagents/setKind": { params: EnabledParams; result: Empty };
  "subagents/setKindTools": { params: SubagentToolsParams; result: Empty };
  "subagents/resetKindTools": { params: IdParams; result: Empty };
  "subagents/kindText": { params: IdParams; result: TextResult };
  "mcp/status": { params: Empty; result: McpStatusView };
  "mcp/tools": { params: IdParams; result: McpToolsView };
  "files/mounts": { params: Empty; result: FileMountsResult };
  "files/list": { params: PathParams; result: FileListResult };
  "files/read": { params: FileReadParams; result: FileReadResult };
  "pages/read": { params: PageReadParams; result: PageReadResult };
  "imageModel/read": { params: Empty; result: ImageModelConfig };
  "imageModel/save": { params: ImageModelSaveParams; result: ImageModelSaveResult };
  "imageModel/models": { params: Empty; result: ModelListResult };
  "imageModel/try": { params: TextParams; result: ImageTryResult };
  "memory/setEnabled": { params: FlagParams; result: Empty };
  "memory/write": { params: MemoryWriteParams; result: MemoryWriteResult };
  "memory/delete": { params: IdParams; result: Empty };
  "memory/clear": { params: Empty; result: DoneResult };
  "memory/lessons": { params: Empty; result: MemoryLessons };
  "modelSettings/read": { params: ModelSettingsParams; result: ModelSettingsResult };
  "modelSettings/save": { params: ModelSettingsSaveParams; result: Empty };
  "webLookup/read": { params: Empty; result: WebLookupView };
  "webLookup/setSearchApi": { params: FlagParams; result: WebLookupView };
  "webLookup/setFetchFallback": { params: FlagParams; result: WebLookupView };
  "preferences/sessionPolicy": { params: SessionPolicy; result: Empty };
  "a2aClient/setEnabled": { params: FlagParams; result: Empty };
  "a2aClient/setSpeakExternal": { params: FlagParams; result: Empty };
  "a2aClient/send": { params: A2aClientTextParams; result: Empty };
  "a2aClient/steer": { params: A2aClientTextParams; result: Empty };
  "a2aClient/answer": { params: A2aClientAnswerParams; result: Empty };
  "a2aClient/cancel": { params: Empty; result: Empty };
  "a2aClient/newConversation": { params: Empty; result: Empty };
  "plans/setEnabled": { params: EnabledParams; result: Empty };
  "plans/delete": { params: IdParams; result: Empty };
  "plans/create": { params: PlanDraft; result: SaveResult };
  "app/inFront": { params: FlagParams; result: Empty };
  "app/languageChanged": { params: Empty; result: Empty };
  "mcp/toolsChanged": { params: IdsParams; result: Empty };
  "model/select": { params: ModelSelectParams; result: Empty };
  "run/set": { params: RunSettingsParams; result: Empty };
  "config/reload": { params: Empty; result: Empty };
  "provider/save": { params: ProviderSaveParams; result: Empty };
  "provider/create": { params: ProviderCreateParams; result: ProviderCreateResult };
  "provider/delete": { params: IdParams; result: Empty };
  "provider/fetchModels": { params: ProviderFetchParams; result: ModelListResult };
  "provider/saveModels": { params: ProviderModelsParams; result: Empty };
  "mcp/setEnabled": { params: EnabledParams; result: Empty };
  "mcp/save": { params: McpServerDraft; result: SaveResult };
  "mcp/delete": { params: IdParams; result: Empty };
  "a2a/profile/setEnabled": { params: EnabledParams; result: Empty };
  "a2a/profile/save": { params: A2aProfileDraft; result: SaveResult };
  "a2a/profile/delete": { params: IdParams; result: Empty };
  "workspace/select": { params: UriParams; result: Empty };
  "workspace/clear": { params: Empty; result: Empty };
  "workspace/unavailable": { params: TextParams; result: Empty };
  "data/clear": { params: DataClearParams; result: DataClearResult };
  "trajectory/refresh": { params: Empty; result: Empty };
  "trajectory/loadOlder": { params: Empty; result: Empty };
  "trajectory/loadRun": { params: IdParams; result: Empty };
  "trajectory/raw": { params: TrajectoryRawParams; result: TrajectoryRaw };
  "question/answer": { params: QuestionAnswerParams; result: Empty };
  "usage/read": { params: Empty; result: UsageData };
  "usage/setPrice": { params: ModelPriceParams; result: Empty };
  "usage/setRate": { params: RateParams; result: Empty };
  "usage/syncPrices": { params: Empty; result: PriceSyncResult };
  "deepseek/balance": { params: DeepSeekBalanceParams; result: DeepSeekBalanceResult };
  "deepseek/probe": { params: ProviderConnection; result: DeepSeekProbeView };
  "deepseek/check": { params: ProviderConnection; result: DeepSeekProbeView };
  "jev/setToolSearch": { params: JevSettings; result: SaveResult };
  "checks/run": { params: IdsParams; result: Empty };
  "companion/read": { params: Empty; result: CompanionView };
  "companion/save": { params: CompanionSaveParams; result: CompanionSaveResult };
  "companion/try": { params: TextParams; result: CompanionTryResult };
  "companion/decisions": { params: Empty; result: CompanionDecisionsResult };
  "connectors/list": { params: Empty; result: ConnectorCatalog };
  "connectors/refreshAll": { params: Empty; result: ConnectorStatuses };
  "connectors/refresh": { params: IdParams; result: ConnectorStatus };
  "connectors/page": { params: IdParams; result: ConnectorPage };
  "connectors/connect": { params: IdParams; result: ConnectorStatus };
  "connectors/newCode": { params: IdParams; result: ConnectorStatus };
  "connectors/signIn": { params: ConnectorSignInParams; result: ConnectorStatus };
  "connectors/signOut": { params: IdParams; result: Empty };
  "connectors/test": { params: IdParams; result: TextResult };
  "connectors/disconnect": { params: IdParams; result: TextResult };
  "connectors/connectApp": { params: ConnectAppParams; result: ConnectorTools };
  "connectors/checkApp": { params: CheckAppParams; result: ConnectorTools };
  "connectors/resumeApp": { params: IdParams; result: ConnectorStatus };
  "onDevice/download": { params: IdParams; result: Empty };
  "onDevice/cancel": { params: IdParams; result: Empty };
  "onDevice/delete": { params: IdParams; result: Empty };
  "onDevice/start": { params: IdParams; result: Empty };
  "onDevice/stop": { params: Empty; result: Empty };
  "onboarding/read": { params: Empty; result: OnboardingView };
  "onboarding/saveModelKey": { params: TextParams; result: CheckLine };
  "onboarding/saveDoubaoKey": { params: TextParams; result: SaveResult };
  "onboarding/finish": { params: Empty; result: Empty };
  "realtime/read": { params: Empty; result: RealtimeView };
  "realtime/save": { params: RealtimeSettings; result: RealtimeView };
  "realtime/test": { params: Empty; result: CheckLine };
  "playground/setReal": { params: FlagParams; result: Empty };
  "voice/wake": { params: VoiceWakeParams; result: VoiceWakeResult };
  "voice/closedByHand": { params: Empty; result: Empty };
  "voice/endWakeSession": { params: Empty; result: Empty };
  "voice/clearNotice": { params: Finding; result: Empty };
  "voice/setWakeWord": { params: FlagParams; result: SaveResult };
  "voice/clearWakeMigration": { params: Empty; result: Empty };
  "voice/interruptSpeech": { params: Empty; result: Empty };
  "voice/toggle": { params: Empty; result: Empty };
  "voice/stop": { params: Empty; result: Empty };
  "voice/beginPress": { params: Empty; result: VoiceWakeResult };
  "voice/releasePress": { params: Empty; result: Empty };
  "voice/cancelPress": { params: Empty; result: Empty };
  "voice/notice": { params: TextParams; result: Empty };
  "voiceSettings/read": { params: Empty; result: VoiceSettingsView };
  "voiceSettings/saveVoice": { params: VoiceSettings; result: VoiceSettingsResult };
  "voiceSettings/chooseTts": { params: TtsChoiceParams; result: VoiceSettingsResult };
  "voiceSettings/save": { params: VoiceSettingsSaveParams; result: VoiceSettingsResult };
  "voiceSettings/deleteKey": { params: VoiceKeyParams; result: VoiceSettingsResult };
  "voiceSettings/setWakeLevel": { params: TextParams; result: VoiceSettingsResult };
  "voiceSettings/preview": { params: Empty; result: FlagParams };
}

export interface ClientNotifications {
  "initialized": { params: Empty };
  "presentation/state": { params: PresentationState };
}

export interface ServerRequests {
  "approval/request": { params: ApprovalRequest; result: ApprovalResponse };
  "presentation/ensureRunning": { params: Empty; result: PresentResult };
  "presentation/start": { params: PresentStartParams; result: PresentResult };
  "presentation/card": { params: PresentCardParams; result: PresentResult };
  "presentation/wake": { params: PresentWakeParams; result: TakenResult };
  "androidUse/begin": { params: Empty; result: Empty };
  "androidUse/point": { params: PointParams; result: Empty };
  "androidUse/drag": { params: DragParams; result: Empty };
  "androidUse/hide": { params: Empty; result: Empty };
  "presentation/previewMascot": { params: MascotParams; result: TakenResult };
}

export interface ServerNotifications {
  "thread/list/changed": { params: ThreadListChanged };
  "thread/selected": { params: ThreadSnapshot };
  "turn/started": { params: TurnStarted };
  "turn/completed": { params: TurnCompleted };
  "item/started": { params: ItemChanged };
  "item/updated": { params: ItemChanged };
  "item/delta": { params: ItemDelta };
  "item/removed": { params: ItemRemoved };
  "state/changed": { params: StateChanged };
  "approval/resolved": { params: ApprovalResolved };
  "presentation/planDue": { params: PlanNoticeEntry };
  "presentation/planRan": { params: PlanRanParams };
  "androidUse/reveal": { params: Empty };
  "voice/dismissed": { params: Empty };
}

/** What an extension section's `data` holds, by its `name`. */
export interface ExtensionSections {
  voice: VoiceSection;
  speech: SpeechSection;
  onDevice: OnDeviceSection;
  checks: ChecksSection;
  toolSearch: ToolSearchSection;
}
