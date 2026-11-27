"use client";

import "@xyflow/react/dist/base.css";
import "./visual-theme.css";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from "react";
import {
  Background,
  BackgroundVariant,
  BaseEdge,
  Controls,
  EdgeLabelRenderer,
  Handle,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  ReactFlowProvider,
  getBezierPath,
  useEdgesState,
  useNodesState,
  useReactFlow,
  type Connection,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
} from "@xyflow/react";
import {
  GitBranchPlus,
  LayoutGrid,
  Link2,
  Pencil,
  RefreshCw,
  Search,
  Sparkles,
  Trash2,
  UserCheck,
  UserPlus,
  Wand2,
  X,
} from "lucide-react";
import { Alert } from "@/components/ui/alert";
import { Badge, type UiStatus } from "@/components/ui/badge";
import { Dialog, DialogClose, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { Select } from "@/components/ui/select";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { useToast } from "@/components/ui/toast";
import {
  VisualControlRibbon,
  useEscapeKey,
  useVisualProjection,
} from "@/components/visual/visual-shared";
import {
  loreApi,
  memoryApi,
  visualApi,
  type BookTreeResponse,
  type ForeshadowingDirective,
  type VisualEntityDetailResponse,
  type VisualGraphEdge,
  type VisualGraphNode,
  type VisualVisibility,
} from "@/lib/api";
import { formatWebError } from "@/lib/error-messages";

const RELATION_PRESETS = ["师徒", "同盟", "仇敌", "挚友", "血亲", "宿敌", "上下级", "暗恋"] as const;

const ROLE_META: Record<string, { label: string; status: UiStatus }> = {
  PROTAGONIST: { label: "主角", status: "success" },
  ANTAGONIST: { label: "反派", status: "danger" },
  SUPPORTING: { label: "配角", status: "info" },
  UNRESOLVED: { label: "待绑定", status: "warning" },
};

const LIFECYCLE_META: Record<string, { label: string; status: UiStatus }> = {
  CORE_PINNED: { label: "常驻核心", status: "success" },
  ACTIVE: { label: "活跃", status: "neutral" },
  DORMANT: { label: "休眠", status: "warning" },
  DEAD: { label: "已退场", status: "danger" },
  UNKNOWN: { label: "状态未记", status: "neutral" },
};

type CharacterNodeData = {
  node: VisualGraphNode;
  dimmed: boolean;
  isSelected: boolean;
  isAiHighlighted: boolean;
};

type RelationEdgeData = {
  edge: VisualGraphEdge;
  dimmed: boolean;
  curveOffset: number;
  isAiHighlighted: boolean;
};

type CharacterFlowNode = Node<CharacterNodeData, "character">;
type RelationFlowEdge = Edge<RelationEdgeData, "relation">;

function layoutStorageKey(bookId: string): string {
  return `foliarc:graph_layout:v1:${bookId}`;
}

function readStoredPositions(bookId: string): Record<string, { x: number; y: number }> {
  if (typeof window === "undefined") return {};
  try {
    const raw = window.localStorage.getItem(layoutStorageKey(bookId));
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, { x: number; y: number }>;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function writeStoredPositions(bookId: string, positions: Record<string, { x: number; y: number }>) {
  if (typeof window === "undefined") return;
  try {
    const entries = Object.entries(positions).slice(0, 300);
    window.localStorage.setItem(layoutStorageKey(bookId), JSON.stringify(Object.fromEntries(entries)));
  } catch {
    // ignore quota errors
  }
}

function computeDeterministicLayout(nodes: VisualGraphNode[]): Record<string, { x: number; y: number }> {
  const result: Record<string, { x: number; y: number }> = {};
  if (nodes.length === 0) return result;

  const groups = new Map<string, VisualGraphNode[]>();
  const sorted = [...nodes].sort((a, b) => {
    const rank = (r: string) => (r === "PROTAGONIST" ? 0 : r === "ANTAGONIST" ? 1 : r === "SUPPORTING" ? 2 : 3);
    if (rank(a.roleType) !== rank(b.roleType)) return rank(a.roleType) - rank(b.roleType);
    if (b.relationCount !== a.relationCount) return b.relationCount - a.relationCount;
    return a.name.localeCompare(b.name, "zh-CN");
  });

  for (const n of sorted) {
    const key = n.faction?.trim() || (n.roleType === "PROTAGONIST" ? "主角核心" : "独立角色");
    const list = groups.get(key) ?? [];
    list.push(n);
    groups.set(key, list);
  }

  const groupEntries = Array.from(groups.entries());
  const colsPerRow = Math.max(1, Math.min(3, Math.ceil(Math.sqrt(groupEntries.length))));
  let currentY = 60;

  for (let rowStart = 0; rowStart < groupEntries.length; rowStart += colsPerRow) {
    const rowGroups = groupEntries.slice(rowStart, rowStart + colsPerRow);
    let maxGroupHeight = 0;
    let currentX = 60;

    for (const [, members] of rowGroups) {
      const innerCols = members.length <= 2 ? members.length : Math.min(3, Math.ceil(Math.sqrt(members.length)));
      const cellW = 260;
      const cellH = 148;
      members.forEach((m, idx) => {
        const col = idx % innerCols;
        const row = Math.floor(idx / innerCols);
        result[m.id] = {
          x: currentX + col * cellW,
          y: currentY + row * cellH,
        };
      });
      const groupRows = Math.ceil(members.length / Math.max(1, innerCols));
      maxGroupHeight = Math.max(maxGroupHeight, groupRows * cellH);
      currentX += Math.max(1, innerCols) * cellW + 96;
    }

    currentY += maxGroupHeight + 88;
  }

  return result;
}

function CharacterGraphNode({ data }: NodeProps<CharacterFlowNode>) {
  const { node, dimmed, isSelected, isAiHighlighted } = data;
  const role = ROLE_META[node.roleType] ?? { label: node.roleType, status: "neutral" as UiStatus };
  const lifecycle = LIFECYCLE_META[node.lifecycleStatus];
  const isDead = node.lifecycleStatus === "DEAD";
  const isDormant = node.lifecycleStatus === "DORMANT";

  return (
    <div
      className={`group relative w-52 rounded-2xl border bg-card px-3.5 py-2.5 text-left transition-all ${
        isSelected
          ? "border-primary ring-2 ring-ring/40 shadow-md"
          : isAiHighlighted
            ? "border-status-ai ring-2 ring-status-ai/30 shadow-md"
            : node.roleType === "PROTAGONIST"
              ? "border-primary/45 shadow-sm hover:border-primary"
              : "border-border shadow-2xs hover:border-primary/50"
      } ${dimmed ? "opacity-30" : isDead ? "opacity-65" : isDormant ? "opacity-80" : "opacity-100"}`}
    >
      <Handle type="target" position={Position.Left} id="l-t" className="!bg-primary" />
      <Handle type="source" position={Position.Right} id="r-s" className="!bg-primary" />
      <Handle type="target" position={Position.Top} id="t-t" className="!bg-primary opacity-0 group-hover:opacity-100" />
      <Handle type="source" position={Position.Bottom} id="b-s" className="!bg-primary opacity-0 group-hover:opacity-100" />

      <div className="flex items-start justify-between gap-1.5">
        <div className="min-w-0">
          <div className={`truncate text-[13px] font-semibold text-foreground ${isDead ? "line-through" : ""}`}>
            {node.name}
          </div>
          <div className="mt-0.5 truncate text-[10.5px] text-muted-foreground">
            {node.faction || node.realmOrLevel || "未归属势力"}
          </div>
        </div>
        <Badge status={role.status} className="shrink-0 px-1.5 py-0 text-[10px]">
          {role.label}
        </Badge>
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[10px] text-muted-foreground">
        <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 font-mono">
          出场 {node.appearanceCount}
        </span>
        <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 font-mono">
          关系 {node.relationCount}
        </span>
        {lifecycle && node.lifecycleStatus !== "ACTIVE" && node.lifecycleStatus !== "UNKNOWN" && (
          <Badge status={lifecycle.status} className="px-1.5 py-0 text-[9.5px]">
            {lifecycle.label}
          </Badge>
        )}
      </div>
    </div>
  );
}

function buildCurvedEdgeGeometry(
  sourceX: number,
  sourceY: number,
  targetX: number,
  targetY: number,
  sourcePosition: Position,
  targetPosition: Position,
  curveOffset: number,
): [string, number, number] {
  if (Math.abs(curveOffset) < 0.01) {
    const [edgePath, labelX, labelY] = getBezierPath({
      sourceX,
      sourceY,
      targetX,
      targetY,
      sourcePosition,
      targetPosition,
    });
    return [edgePath, labelX, labelY];
  }

  const dx = targetX - sourceX;
  const dy = targetY - sourceY;
  const dist = Math.hypot(dx, dy) || 1;
  // Unit perpendicular vector (canonicalized so opposite directions spread apart symmetrically)
  const nx = -dy / dist;
  const ny = dx / dist;
  const lift = curveOffset * Math.min(72, Math.max(46, dist * 0.22));
  const midX = (sourceX + targetX) / 2;
  const midY = (sourceY + targetY) / 2;
  const ctrlX = midX + nx * lift;
  const ctrlY = midY + ny * lift;

  const edgePath = `M ${sourceX} ${sourceY} Q ${ctrlX} ${ctrlY} ${targetX} ${targetY}`;
  // Exact quadratic Bezier point at t = 0.5
  const labelX = 0.25 * sourceX + 0.5 * ctrlX + 0.25 * targetX;
  const labelY = 0.25 * sourceY + 0.5 * ctrlY + 0.25 * targetY;
  return [edgePath, labelX, labelY];
}

function RelationGraphEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  selected,
  data,
}: EdgeProps<RelationFlowEdge>) {
  const curveOffset = data?.curveOffset ?? 0;
  const [edgePath, labelX, labelY] = buildCurvedEdgeGeometry(
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    curveOffset,
  );

  const edge = data?.edge;
  const dimmed = Boolean(data?.dimmed);
  const isAiHighlighted = Boolean(data?.isAiHighlighted);
  const isAi = edge?.source === "AI_EXTRACTED";

  const strokeColor = selected
    ? "var(--primary)"
    : isAiHighlighted
      ? "var(--status-ai)"
      : isAi
        ? "var(--status-ai)"
        : "var(--muted-foreground)";

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        markerEnd={markerEnd}
        style={{
          stroke: strokeColor,
          strokeWidth: selected || isAiHighlighted ? 2.6 : 1.6,
          strokeDasharray: isAi ? "5 4" : undefined,
          opacity: dimmed ? 0.18 : selected || isAiHighlighted ? 1 : 0.8,
        }}
      />
      {edge && (
        <EdgeLabelRenderer>
          <div
            style={{
              position: "absolute",
              transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
              pointerEvents: "all",
            }}
            className={`nodrag nopan rounded-full border px-2 py-0.5 text-[10.5px] font-medium shadow-2xs transition-all ${
              selected
                ? "border-primary bg-primary-soft text-primary-soft-foreground"
                : isAiHighlighted
                  ? "border-status-ai bg-status-ai-bg text-status-ai ring-2 ring-status-ai/25"
                  : isAi
                    ? "border-status-ai-border bg-status-ai-bg text-status-ai"
                    : "border-border bg-card text-foreground"
            } ${dimmed ? "opacity-25" : "opacity-100"}`}
          >
            {edge.relationType}
            {edge.sinceChapterNo != null && edge.sinceChapterNo > 0 && (
              <span className="ml-1 font-mono text-[9.5px] opacity-70">
                Ch.{edge.sinceChapterNo}
              </span>
            )}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}

const NODE_TYPES = { character: CharacterGraphNode };
const EDGE_TYPES = { relation: RelationGraphEdge };

export interface CharacterRelationGraphProps {
  bookId: string;
  tree: BookTreeResponse | null;
  activeChapterId: string | null;
  activeSectionId: string | null;
  visibility: VisualVisibility;
  onVisibilityChange: (next: VisualVisibility) => void;
  asOfChapterId: string | null;
  onAsOfChapterIdChange: (chapterId: string | null) => void;
  refreshSignal?: number;
  highlightedRelationIds?: string[];
  onClearHighlightedRelations?: () => void;
  onDataMutated?: () => void;
  onJumpToChapter: (chapterId: string, sectionId?: string | null) => void;
  onSendToDirector: (directive: ForeshadowingDirective, label: string) => void;
  onOpenLorePanel: () => void;
}

function CharacterRelationGraphInner({
  bookId,
  tree,
  activeChapterId,
  activeSectionId,
  visibility,
  onVisibilityChange,
  asOfChapterId,
  onAsOfChapterIdChange,
  refreshSignal = 0,
  highlightedRelationIds = [],
  onClearHighlightedRelations,
  onDataMutated,
  onJumpToChapter,
  onSendToDirector,
  onOpenLorePanel,
}: CharacterRelationGraphProps) {
  const toast = useToast();
  const confirm = useConfirm();
  const { fitView } = useReactFlow();

  const {
    allChapters,
    effectiveChapterId,
    scrubIdx,
    setScrubIdx,
    displayedIdx,
    displayedChapter,
    projection,
    loading,
    refreshing,
    error,
    refetch,
  } = useVisualProjection({
    bookId,
    view: "graph",
    tree,
    activeChapterId,
    activeSectionId,
    visibility,
    asOfChapterId,
    refreshSignal,
    errorFallback: "加载人物关系图谱失败",
  });

  const [searchTerm, setSearchTerm] = useState("");
  const [selectedFaction, setSelectedFaction] = useState<string>("ALL");

  // Selection & Inspector
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [entityDetail, setEntityDetail] = useState<VisualEntityDetailResponse | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);

  // Create / Edit relation & Quick Create Character modal states
  const [connectDraft, setConnectDraft] = useState<{
    sourceName: string;
    targetName: string;
    relationType: string;
    description: string;
    sinceChapterNo: string;
  } | null>(null);
  const [savingRelation, setSavingRelation] = useState(false);

  const [charDraft, setCharDraft] = useState<{
    name: string;
    roleType: string;
    faction: string;
    realmOrLevel: string;
    aliases: string;
    currentLocation: string;
  } | null>(null);
  const [savingChar, setSavingChar] = useState(false);
  const [extractingMemory, setExtractingMemory] = useState(false);

  const [editingEdge, setEditingEdge] = useState<{
    id: string;
    sourceCharacter: string;
    targetCharacter: string;
    relationType: string;
    description: string;
    sinceChapterNo: string;
    expectedUpdatedAt: string | null;
  } | null>(null);

  const [flowNodes, setFlowNodes, onNodesChange] = useNodesState<CharacterFlowNode>([]);
  const [flowEdges, setFlowEdges, onEdgesChange] = useEdgesState<RelationFlowEdge>([]);

  const positionsRef = useRef<Record<string, { x: number; y: number }>>({});
  const extractTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (extractTimerRef.current) clearTimeout(extractTimerRef.current);
    };
  }, []);

  useEscapeKey(!charDraft && !connectDraft && Boolean(selectedNodeId || selectedEdgeId), () => {
    setSelectedNodeId(null);
    setSelectedEdgeId(null);
    setEditingEdge(null);
  });

  const selectedNode = useMemo(
    () => projection?.nodes.find((n) => n.id === selectedNodeId) ?? null,
    [projection, selectedNodeId],
  );
  const selectedEdge = useMemo(
    () => projection?.edges.find((e) => e.id === selectedEdgeId) ?? null,
    [projection, selectedEdgeId],
  );

  // Fetch entity detail when a character node is selected
  useEffect(() => {
    const charId = selectedNode?.characterId;
    if (!charId) return;
    const controller = new AbortController();
    let active = true;
    const useSection =
      visibility === "AS_OF" &&
      effectiveChapterId &&
      effectiveChapterId === activeChapterId &&
      activeSectionId
        ? activeSectionId
        : null;
    void visualApi
      .getEntityDetail(
        bookId,
        "character",
        charId,
        {
          visibility,
          asOfChapterId: visibility === "AS_OF" ? effectiveChapterId : undefined,
          asOfSectionId: useSection,
        },
        { signal: controller.signal },
      )
      .then((detail) => {
        if (active) {
          setEntityDetail(detail);
          setLoadingDetail(false);
        }
      })
      .catch(() => {
        if (active && !controller.signal.aborted) {
          setEntityDetail(null);
          setLoadingDetail(false);
        }
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [bookId, selectedNode?.characterId, visibility, effectiveChapterId, activeChapterId, activeSectionId]);

  const visibleEntityDetail =
    selectedNode?.characterId && entityDetail?.id === selectedNode.characterId
      ? entityDetail
      : null;

  // Filtered nodes/edges + neighbor focus + AI highlight set
  const filteredGraph = useMemo(() => {
    if (!projection) return { nodes: [] as VisualGraphNode[], edges: [] as VisualGraphEdge[] };
    const q = searchTerm.trim().toLowerCase();
    const visibleNodes = projection.nodes.filter((n) => {
      if (selectedFaction !== "ALL" && (n.faction || "独立角色") !== selectedFaction) {
        return false;
      }
      if (q && !n.name.toLowerCase().includes(q) && !(n.faction ?? "").toLowerCase().includes(q)) {
        return false;
      }
      return true;
    });
    const visibleIds = new Set(visibleNodes.map((n) => n.id));
    const visibleEdges = projection.edges.filter(
      (e) => visibleIds.has(e.sourceId) && visibleIds.has(e.targetId),
    );
    return { nodes: visibleNodes, edges: visibleEdges };
  }, [projection, searchTerm, selectedFaction]);

  const aiHighlightedEdgeSet = useMemo(
    () => new Set(highlightedRelationIds),
    [highlightedRelationIds],
  );

  const aiHighlightedNodeSet = useMemo(() => {
    if (aiHighlightedEdgeSet.size === 0) return null;
    const set = new Set<string>();
    for (const e of filteredGraph.edges) {
      if (aiHighlightedEdgeSet.has(e.id)) {
        set.add(e.sourceId);
        set.add(e.targetId);
      }
    }
    return set.size > 0 ? set : null;
  }, [aiHighlightedEdgeSet, filteredGraph.edges]);

  const matchedHighlightCount = useMemo(() => {
    if (aiHighlightedEdgeSet.size === 0) return 0;
    return filteredGraph.edges.filter((e) => aiHighlightedEdgeSet.has(e.id)).length;
  }, [aiHighlightedEdgeSet, filteredGraph.edges]);

  const neighborIds = useMemo(() => {
    if (!selectedNodeId) return null;
    const set = new Set<string>([selectedNodeId]);
    for (const e of filteredGraph.edges) {
      if (e.sourceId === selectedNodeId) set.add(e.targetId);
      if (e.targetId === selectedNodeId) set.add(e.sourceId);
    }
    return set;
  }, [selectedNodeId, filteredGraph.edges]);

  // Compute symmetric curve offsets for parallel / bidirectional edges so they never collide
  const edgeCurveOffsets = useMemo(() => {
    const groups = new Map<string, VisualGraphEdge[]>();
    for (const e of filteredGraph.edges) {
      const pairKey =
        e.sourceId < e.targetId
          ? `${e.sourceId}\u0000${e.targetId}`
          : `${e.targetId}\u0000${e.sourceId}`;
      const list = groups.get(pairKey) ?? [];
      list.push(e);
      groups.set(pairKey, list);
    }
    const offsets = new Map<string, number>();
    for (const group of groups.values()) {
      if (group.length <= 1) {
        if (group[0]) offsets.set(group[0].id, 0);
        continue;
      }
      group.forEach((edge, idx) => {
        const rawSlot = idx - (group.length - 1) / 2;
        // Align normal orientation relative to canonical pair ordering so reverse edges bend cleanly
        const directionSign = edge.sourceId < edge.targetId ? 1 : -1;
        offsets.set(edge.id, rawSlot * directionSign);
      });
    }
    return offsets;
  }, [filteredGraph.edges]);

  // Sync React Flow nodes & edges whenever projection or selection changes
  useEffect(() => {
    const stored = readStoredPositions(bookId);
    const computed = computeDeterministicLayout(filteredGraph.nodes);
    const mergedPositions: Record<string, { x: number; y: number }> = {
      ...computed,
      ...stored,
      ...positionsRef.current,
    };

    const nextNodes: CharacterFlowNode[] = filteredGraph.nodes.map((n) => {
      const isAiNode = Boolean(aiHighlightedNodeSet?.has(n.id));
      const dimmedBySelection = Boolean(neighborIds && !neighborIds.has(n.id));
      const dimmedByAi = Boolean(!selectedNodeId && aiHighlightedNodeSet && !isAiNode);
      return {
        id: n.id,
        type: "character",
        position: mergedPositions[n.id] ?? computed[n.id] ?? { x: 80, y: 80 },
        selected: n.id === selectedNodeId,
        data: {
          node: n,
          isSelected: n.id === selectedNodeId,
          isAiHighlighted: isAiNode,
          dimmed: dimmedBySelection || dimmedByAi,
        },
      };
    });

    const nextEdges: RelationFlowEdge[] = filteredGraph.edges.map((e) => {
      const touchesFocus =
        !selectedNodeId || e.sourceId === selectedNodeId || e.targetId === selectedNodeId;
      const isAiHit = aiHighlightedEdgeSet.has(e.id);
      const dimmedBySelection = Boolean(selectedNodeId && !touchesFocus);
      const dimmedByAi = Boolean(!selectedNodeId && aiHighlightedNodeSet && !isAiHit);
      return {
        id: e.id,
        type: "relation",
        source: e.sourceId,
        target: e.targetId,
        selected: e.id === selectedEdgeId,
        markerEnd: {
          type: MarkerType.ArrowClosed,
          width: 16,
          height: 16,
          color:
            e.id === selectedEdgeId
              ? "var(--primary)"
              : isAiHit
                ? "var(--status-ai)"
                : "var(--muted-foreground)",
        },
        data: {
          edge: e,
          dimmed: dimmedBySelection || dimmedByAi,
          curveOffset: edgeCurveOffsets.get(e.id) ?? 0,
          isAiHighlighted: isAiHit,
        },
      };
    });

    setFlowNodes(nextNodes);
    setFlowEdges(nextEdges);
  }, [
    bookId,
    filteredGraph,
    selectedNodeId,
    selectedEdgeId,
    neighborIds,
    aiHighlightedEdgeSet,
    aiHighlightedNodeSet,
    edgeCurveOffsets,
    setFlowNodes,
    setFlowEdges,
  ]);

  const handleNodeDragStop = useCallback(
    (_: unknown, dragged: CharacterFlowNode) => {
      const next = {
        ...readStoredPositions(bookId),
        ...positionsRef.current,
        [dragged.id]: {
          x: Math.round(dragged.position.x),
          y: Math.round(dragged.position.y),
        },
      };
      positionsRef.current = next;
      writeStoredPositions(bookId, next);
    },
    [bookId],
  );

  const handleResetLayout = useCallback(() => {
    positionsRef.current = {};
    if (typeof window !== "undefined") {
      window.localStorage.removeItem(layoutStorageKey(bookId));
    }
    const fresh = computeDeterministicLayout(filteredGraph.nodes);
    positionsRef.current = fresh;
    setFlowNodes((prev) =>
      prev.map((item) => ({
        ...item,
        position: fresh[item.id] ?? item.position,
      })),
    );
    window.requestAnimationFrame(() => {
      void fitView({ padding: 0.22, duration: 280 });
    });
  }, [bookId, filteredGraph.nodes, setFlowNodes, fitView]);

  const handleConnect = useCallback(
    (connection: Connection) => {
      if (!connection.source || !connection.target || connection.source === connection.target) {
        return;
      }
      const src = projection?.nodes.find((n) => n.id === connection.source);
      const dst = projection?.nodes.find((n) => n.id === connection.target);
      if (!src || !dst) return;
      setConnectDraft({
        sourceName: src.name,
        targetName: dst.name,
        relationType: "同盟",
        description: "",
        sinceChapterNo: String(projection?.cutoff.chapterNo || displayedChapter?.chapterNo || 1),
      });
    },
    [projection, displayedChapter],
  );

  async function handleTriggerExtract() {
    const targetChapterId = effectiveChapterId ?? activeChapterId;
    if (!targetChapterId) {
      toast.info("请先在左侧目录新建并撰写章节");
      return;
    }
    setExtractingMemory(true);
    try {
      await memoryApi.triggerExtract(bookId, targetChapterId);
      toast.success("已提交本章人物与关系抽取", {
        description: "AI 正在后台解析正文，数秒后自动刷新图谱",
      });
      if (extractTimerRef.current) clearTimeout(extractTimerRef.current);
      extractTimerRef.current = setTimeout(() => {
        void refetch();
        onDataMutated?.();
      }, 3500);
    } catch (err) {
      toast.error(formatWebError(err, "提交记忆抽取失败，请稍后重试"));
    } finally {
      setExtractingMemory(false);
    }
  }

  async function handleSubmitQuickCharacter(e: FormEvent) {
    e.preventDefault();
    if (!charDraft || !charDraft.name.trim()) return;
    setSavingChar(true);
    try {
      const customAttributes: Record<string, string> = {};
      if (charDraft.faction.trim()) customAttributes.faction = charDraft.faction.trim();
      if (charDraft.realmOrLevel.trim()) customAttributes.realm = charDraft.realmOrLevel.trim();
      await loreApi.upsertCharacter(bookId, {
        name: charDraft.name.trim(),
        roleType: charDraft.roleType,
        aliases: charDraft.aliases.trim(),
        realmOrLevel: charDraft.realmOrLevel.trim(),
        currentLocation: charDraft.currentLocation.trim(),
        customAttributes,
      });
      const createdName = charDraft.name.trim();
      setCharDraft(null);
      if (visibility === "AS_OF" && filteredGraph.nodes.length === 0) {
        onVisibilityChange("PLAN");
        toast.success(`已创建角色「${createdName}」`);
      } else {
        toast.success(`已创建角色「${createdName}」`);
      }
      onDataMutated?.();
      await refetch();
    } catch (err) {
      toast.error(formatWebError(err, "创建角色失败，请稍后重试"));
    } finally {
      setSavingChar(false);
    }
  }

  async function handleSubmitNewRelation(e: FormEvent) {
    e.preventDefault();
    if (!connectDraft || !connectDraft.sourceName.trim() || !connectDraft.targetName.trim() || !connectDraft.relationType.trim()) {
      return;
    }
    if (connectDraft.sourceName.trim() === connectDraft.targetName.trim()) {
      toast.error("源角色与目标角色不能相同");
      return;
    }
    setSavingRelation(true);
    try {
      const parsedChapter = Number.parseInt(connectDraft.sinceChapterNo, 10);
      await memoryApi.upsertRelation(bookId, {
        sourceCharacter: connectDraft.sourceName.trim(),
        targetCharacter: connectDraft.targetName.trim(),
        relationType: connectDraft.relationType.trim(),
        description: connectDraft.description.trim(),
        sinceChapterNo: Number.isNaN(parsedChapter) ? undefined : Math.max(0, parsedChapter),
        sourceSectionId:
          effectiveChapterId === activeChapterId && activeSectionId ? activeSectionId : undefined,
      });
      setConnectDraft(null);
      toast.success("已保存人物关系");
      onDataMutated?.();
      await refetch();
    } catch (err) {
      toast.error(formatWebError(err, "保存人物关系失败，请稍后重试"));
    } finally {
      setSavingRelation(false);
    }
  }

  async function handleSaveEdgeEdit(e: FormEvent) {
    e.preventDefault();
    if (!editingEdge) return;
    setSavingRelation(true);
    try {
      const parsedChapter = Number.parseInt(editingEdge.sinceChapterNo, 10);
      await visualApi.updateRelation(bookId, editingEdge.id, {
        sourceCharacter: editingEdge.sourceCharacter.trim(),
        targetCharacter: editingEdge.targetCharacter.trim(),
        relationType: editingEdge.relationType.trim(),
        description: editingEdge.description.trim(),
        sinceChapterNo: Number.isNaN(parsedChapter) ? null : Math.max(0, parsedChapter),
        expectedUpdatedAt: editingEdge.expectedUpdatedAt,
      });
      setEditingEdge(null);
      toast.success("人物关系已更新");
      onDataMutated?.();
      await refetch();
    } catch (err) {
      toast.error(formatWebError(err, "更新人物关系失败，请稍后重试"));
    } finally {
      setSavingRelation(false);
    }
  }

  async function handleConfirmAiEdge(edge: VisualGraphEdge) {
    setSavingRelation(true);
    try {
      const confirmed = await visualApi.confirmRelation(bookId, edge.id);
      setSelectedEdgeId(confirmed.id);
      toast.success("已转为作者确认关系");
      onDataMutated?.();
      await refetch();
    } catch (err) {
      toast.error(formatWebError(err, "确认关系失败，请稍后重试"));
    } finally {
      setSavingRelation(false);
    }
  }

  async function handleDeleteEdge(edge: VisualGraphEdge) {
    await confirm({
      title: `删除关系「${edge.sourceName} — ${edge.relationType} → ${edge.targetName}」？`,
      description: "删除后将从人物关系网和 AI 长记忆中移除。",
      confirmLabel: "确认删除",
      variant: "destructive",
      onConfirm: async () => {
        await memoryApi.deleteRelation(bookId, edge.id);
        setSelectedEdgeId(null);
        setEditingEdge(null);
        toast.success("已删除人物关系");
        onDataMutated?.();
        await refetch();
      },
    });
  }

  const factionFilters = useMemo(() => {
    const set = new Set<string>();
    for (const n of projection?.nodes ?? []) {
      set.add(n.faction || "独立角色");
    }
    return ["ALL", ...Array.from(set)];
  }, [projection]);

  return (
    <div className="relative flex min-h-0 flex-1 flex-col overflow-hidden bg-surface-subtle">
      {/* Shared Control Ribbon */}
      <VisualControlRibbon
        visibility={visibility}
        onVisibilityChange={onVisibilityChange}
        allChapters={allChapters}
        displayedIdx={displayedIdx}
        displayedChapter={displayedChapter}
        scrubIdx={scrubIdx}
        onScrubChange={setScrubIdx}
        onScrubCommit={(chapterId) => {
          onAsOfChapterIdChange(chapterId);
          setScrubIdx(null);
        }}
        sliderAriaLabel="防剧透章节时间轴滑块"
        showChapterTitle
        refreshing={refreshing}
        leftExtra={
          <div className="relative">
            <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
            <input
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder="筛选人物或势力..."
              className="h-7 w-36 rounded-lg border border-border bg-background pr-2.5 pl-7 text-[11.5px] text-foreground placeholder:text-muted-foreground/75 outline-none focus:border-ring"
            />
          </div>
        }
        rightActions={
          <>
            {projection && (
              <span className="hidden font-mono text-[11px] text-muted-foreground xl:inline">
                {projection.visibleCharacters} 人物 · {projection.edges.length} 关系
              </span>
            )}
            {effectiveChapterId && (
              <Button
                size="xs"
                variant="ghost"
                loading={extractingMemory}
                onClick={() => void handleTriggerExtract()}
                title="从当前章节正文抽取人物状态与关系网"
              >
                <Wand2 className="size-3.5 text-primary" />
                <span className="hidden md:inline">抽取记忆</span>
              </Button>
            )}
            <Button
              size="xs"
              variant="ghost"
              onClick={() =>
                setCharDraft({
                  name: "",
                  roleType: "SUPPORTING",
                  faction: "",
                  realmOrLevel: "",
                  aliases: "",
                  currentLocation: "",
                })
              }
              title="快速新建角色"
            >
              <UserPlus className="size-3.5" />
              <span className="hidden sm:inline">新建角色</span>
            </Button>
            <Button size="xs" variant="ghost" onClick={handleResetLayout} title="重新整理布局">
              <LayoutGrid className="size-3.5" />
              <span className="hidden lg:inline">整理布局</span>
            </Button>
            <Button size="xs" variant="ghost" onClick={() => void refetch()} title="刷新图谱">
              <RefreshCw className={`size-3.5 ${loading || refreshing ? "animate-spin" : ""}`} />
            </Button>
            <Button
              size="xs"
              onClick={() => {
                const first = projection?.nodes[0]?.name ?? "";
                const second = projection?.nodes[1]?.name ?? "";
                setConnectDraft({
                  sourceName: first,
                  targetName: second,
                  relationType: "同盟",
                  description: "",
                  sinceChapterNo: String(projection?.cutoff.chapterNo || displayedChapter?.chapterNo || 1),
                });
              }}
            >
              <GitBranchPlus className="size-3.5" />
              新增关系
            </Button>
          </>
        }
      />

      {/* Faction Filter Strip */}
      {factionFilters.length > 2 && (
        <div className="flex items-center gap-1.5 overflow-x-auto border-b border-border/70 bg-card/70 px-3.5 py-1.5 text-[11px]">
          <span className="shrink-0 text-muted-foreground">势力：</span>
          {factionFilters.map((f) => {
            const active = selectedFaction === f;
            return (
              <button
                key={f}
                type="button"
                onClick={() => setSelectedFaction(f)}
                className={`shrink-0 rounded-full px-2.5 py-0.5 font-medium transition-colors ${
                  active
                    ? "bg-primary-soft text-primary-soft-foreground"
                    : "text-muted-foreground hover:bg-muted hover:text-foreground"
                }`}
              >
                {f === "ALL" ? "全部" : f}
              </button>
            );
          })}
          {projection && projection.undatedRelationCount > 0 && visibility === "AS_OF" && (
            <span className="ml-auto shrink-0 text-[10.5px] text-muted-foreground">
              另有 {projection.undatedRelationCount} 条未标章关系（切「全书规划」可查看）
            </span>
          )}
        </div>
      )}

      {error && (
        <div className="p-3">
          <Alert status="danger" announce="assertive">
            {error}
          </Alert>
        </div>
      )}

      {/* Main Graph Viewport + Floating Canvas Inspector Overlay */}
      <div className="relative flex min-h-0 flex-1 overflow-hidden">
        <div className="foliarc-flow relative min-h-0 flex-1">
          {!loading && filteredGraph.nodes.length === 0 ? (
            <div className="flex h-full items-center justify-center p-8">
              {(projection?.nodes.length ?? 0) > 0 &&
              (searchTerm.trim() !== "" || selectedFaction !== "ALL") ? (
                <EmptyState
                  title="没有符合筛选条件的人物"
                  action={
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => {
                        setSearchTerm("");
                        setSelectedFaction("ALL");
                      }}
                    >
                      清除筛选
                    </Button>
                  }
                />
              ) : (
                <EmptyState
                  title={
                    visibility === "AS_OF" ? "截至当前章暂无出场人物" : "暂无人物与关系"
                  }
                  action={
                    <div className="flex flex-wrap items-center justify-center gap-2">
                      {effectiveChapterId && (
                        <Button
                          size="sm"
                          loading={extractingMemory}
                          onClick={() => void handleTriggerExtract()}
                        >
                          <Wand2 className="size-3.5" />
                          抽取本章人物
                        </Button>
                      )}
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={() =>
                          setCharDraft({
                            name: "",
                            roleType: "PROTAGONIST",
                            faction: "",
                            realmOrLevel: "",
                            aliases: "",
                            currentLocation: "",
                          })
                        }
                      >
                        <UserPlus className="size-3.5" />
                        新建角色
                      </Button>
                      {visibility === "AS_OF" && (
                        <Button size="sm" variant="outline" onClick={() => onVisibilityChange("PLAN")}>
                          查看全书规划
                        </Button>
                      )}
                    </div>
                  }
                />
              )}
            </div>
          ) : (
            <ReactFlow
              nodes={flowNodes}
              edges={flowEdges}
              nodeTypes={NODE_TYPES}
              edgeTypes={EDGE_TYPES}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onNodeDragStop={handleNodeDragStop}
              onConnect={handleConnect}
              onNodeClick={(_, node) => {
                setSelectedNodeId(node.id);
                setSelectedEdgeId(null);
                setEditingEdge(null);
                setLoadingDetail(Boolean(node.data.node.characterId));
              }}
              onEdgeClick={(_, edge) => {
                setSelectedEdgeId(edge.id);
                setSelectedNodeId(null);
                setEditingEdge(null);
              }}
              onPaneClick={() => {
                setSelectedNodeId(null);
                setSelectedEdgeId(null);
                setEditingEdge(null);
              }}
              fitView
              fitViewOptions={{ padding: 0.24 }}
              minZoom={0.25}
              maxZoom={1.8}
              proOptions={{ hideAttribution: true }}
            >
              <Background variant={BackgroundVariant.Dots} gap={22} size={1.2} />
              <Controls showInteractive={false} />
              <MiniMap
                pannable
                zoomable
                nodeColor={(n) => {
                  const role = (n.data as CharacterNodeData | undefined)?.node?.roleType;
                  return role === "PROTAGONIST"
                    ? "var(--primary)"
                    : role === "ANTAGONIST"
                      ? "var(--status-danger)"
                      : "var(--muted-foreground)";
                }}
              />
            </ReactFlow>
          )}

          {/* AI Cited Relations Highlight Pill */}
          {highlightedRelationIds.length > 0 && (
            <div className="absolute top-3 left-3 z-10 flex items-center gap-2 rounded-full border border-status-ai-border bg-status-ai-bg/95 px-3 py-1 text-[11px] font-medium text-status-ai shadow-sm backdrop-blur-sm">
              <Sparkles className="size-3.5 shrink-0" />
              <span>
                {matchedHighlightCount > 0
                  ? `已高亮本次引用 ${matchedHighlightCount} 条关系`
                  : `本次引用 ${highlightedRelationIds.length} 条关系`}
              </span>
              {onClearHighlightedRelations && (
                <button
                  type="button"
                  onClick={onClearHighlightedRelations}
                  className="ml-1 rounded-full px-1.5 py-0.5 text-[10px] hover:bg-card/60"
                >
                  清除
                </button>
              )}
            </div>
          )}
        </div>

        {/* Floating Overlay Inspector (never squeezes the graph canvas on narrow viewports) */}
        {(selectedNode || selectedEdge) && (
          <div className="absolute top-3 right-3 bottom-3 z-20 flex w-80 max-w-[calc(100%-1.5rem)] flex-col overflow-hidden rounded-2xl border border-border bg-card/95 shadow-xl backdrop-blur-md">
            <div className="flex items-center justify-between border-b border-border px-3.5 py-2.5">
              <div className="min-w-0">
                <div className="truncate text-xs font-semibold text-foreground">
                  {selectedNode
                    ? selectedNode.name
                    : selectedEdge
                      ? `${selectedEdge.sourceName} → ${selectedEdge.targetName}`
                      : ""}
                </div>
                <div className="text-[10.5px] text-muted-foreground">
                  {selectedNode
                    ? `${ROLE_META[selectedNode.roleType]?.label ?? selectedNode.roleType} · ${
                        visibility === "AS_OF" ? `截至第 ${projection?.cutoff.chapterNo ?? 0} 章` : "全书规划"
                      }`
                    : "人物关系"}
                </div>
              </div>
              <Button
                size="icon-xs"
                variant="ghost"
                title="关闭详情 (Esc)"
                onClick={() => {
                  setSelectedNodeId(null);
                  setSelectedEdgeId(null);
                  setEditingEdge(null);
                }}
              >
                <X className="size-3.5" />
              </Button>
            </div>

            <div className="flex-1 space-y-3.5 overflow-y-auto p-3.5 text-xs">
              {selectedNode && (
                <>
                  <div className="space-y-1.5 rounded-xl border border-border bg-surface-subtle p-2.5 text-[11.5px]">
                    <div className="flex items-center justify-between">
                      <span className="text-muted-foreground">势力 / 境界</span>
                      <span className="font-medium text-foreground">
                        {selectedNode.faction || selectedNode.realmOrLevel || "未登记"}
                      </span>
                    </div>
                    <div className="flex items-center justify-between">
                      <span className="text-muted-foreground">所在位置</span>
                      <span className="font-medium text-foreground">
                        {selectedNode.currentLocation || "未记录"}
                      </span>
                    </div>
                    <div className="flex items-center justify-between">
                      <span className="text-muted-foreground">此时状态</span>
                      <span className="font-medium text-foreground">
                        {selectedNode.statusSummary || "当时状态未记录"}
                      </span>
                    </div>
                  </div>

                  {/* Clickable Chapter Appearance Heat Strip */}
                  <div className="space-y-2">
                    <div className="font-semibold text-foreground">出场章节</div>
                    {loadingDetail ? (
                      <div className="text-[11px] text-muted-foreground">加载中…</div>
                    ) : visibleEntityDetail && visibleEntityDetail.heatByChapter.length > 0 ? (
                      <div className="flex flex-wrap gap-1">
                        {visibleEntityDetail.heatByChapter.map((cell) => (
                          <button
                            key={cell.chapterNo}
                            type="button"
                            disabled={!cell.chapterId}
                            title={`第 ${cell.chapterNo} 章 ${cell.chapterTitle}（${cell.evidenceCount} 条证据）`}
                            onClick={() => {
                              if (cell.chapterId) {
                                onJumpToChapter(cell.chapterId, cell.firstSectionId);
                              }
                            }}
                            className={`rounded-md border px-2 py-1 font-mono text-[10.5px] font-medium transition-colors ${
                              cell.evidenceCount >= 3
                                ? "border-primary bg-primary text-primary-foreground"
                                : "border-primary/40 bg-primary-soft text-primary-soft-foreground hover:border-primary"
                            }`}
                          >
                            Ch.{cell.chapterNo}
                          </button>
                        ))}
                      </div>
                    ) : (
                      <p className="text-[11px] text-muted-foreground">暂无出场记录</p>
                    )}
                  </div>

                  {/* Related Foreshadowings */}
                  {visibleEntityDetail && visibleEntityDetail.relatedArcs.length > 0 && (
                    <div className="space-y-1.5">
                      <div className="font-semibold text-foreground">
                        关联伏笔 ({visibleEntityDetail.relatedArcs.length})
                      </div>
                      <ul className="space-y-1.5">
                        {visibleEntityDetail.relatedArcs.map((arc) => (
                          <li
                            key={arc.id}
                            className="rounded-xl border border-border bg-surface-subtle p-2.5"
                          >
                            <div className="flex items-center justify-between gap-1.5">
                              <span className="truncate font-medium text-foreground">
                                {arc.code ? `${arc.code} ` : ""}
                                {arc.title}
                              </span>
                              <Badge
                                status={
                                  arc.alertState === "RESOLVED"
                                    ? "success"
                                    : arc.alertState === "OVERDUE"
                                      ? "danger"
                                      : arc.alertState === "DUE"
                                        ? "warning"
                                        : "info"
                                }
                                className="text-[9.5px]"
                              >
                                {arc.alertState === "RESOLVED"
                                  ? "已回收"
                                  : arc.alertState === "OVERDUE"
                                    ? "逾期"
                                    : arc.alertState === "DUE"
                                      ? "到期"
                                      : "进行中"}
                              </Badge>
                            </div>
                            {arc.allowedDirectorModes.length > 0 && (
                              <div className="mt-2 flex flex-wrap gap-1">
                                {arc.allowedDirectorModes.map((m) => (
                                  <Button
                                    key={m}
                                    size="xs"
                                    variant="secondary"
                                    onClick={() =>
                                      onSendToDirector(
                                        {
                                          foreshadowingId: arc.id,
                                          mode: m,
                                          resolveType: m === "PAYOFF" ? "FULL_RESOLVE" : undefined,
                                        },
                                        arc.title,
                                      )
                                    }
                                    className="h-6 px-2 text-[10px]"
                                  >
                                    <Sparkles className="size-3" />
                                    {m === "PLANT" ? "本章埋入" : m === "HINT" ? "本章照应" : "本章回收"}
                                  </Button>
                                ))}
                              </div>
                            )}
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}

                  {/* Evidence snippets */}
                  {visibleEntityDetail && visibleEntityDetail.evidence.length > 0 && (
                    <div className="space-y-1.5">
                      <div className="font-semibold text-foreground">
                        关键证据 ({visibleEntityDetail.evidence.length})
                      </div>
                      <ul className="space-y-1.5">
                        {visibleEntityDetail.evidence.slice(0, 6).map((ev, idx) => (
                          <li
                            key={`${ev.kind}-${idx}`}
                            className="rounded-lg border border-border bg-surface-subtle p-2 text-[11px]"
                          >
                            <div className="flex items-center justify-between gap-1">
                              <span className="font-medium text-foreground">{ev.title}</span>
                              {ev.chapterNo != null && (
                                <button
                                  type="button"
                                  disabled={!ev.chapterId}
                                  onClick={() => {
                                    if (ev.chapterId) onJumpToChapter(ev.chapterId, ev.sectionId);
                                  }}
                                  className="font-mono text-[10px] text-primary hover:underline"
                                >
                                  Ch.{ev.chapterNo}
                                </button>
                              )}
                            </div>
                            {ev.snippet && (
                              <p className="mt-0.5 line-clamp-2 text-[10.5px] text-muted-foreground">
                                {ev.snippet}
                              </p>
                            )}
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}

                  <div className="flex gap-1.5 pt-1">
                    <Button
                      size="xs"
                      variant="secondary"
                      className="flex-1"
                      onClick={() => {
                        setConnectDraft({
                          sourceName: selectedNode.name,
                          targetName: "",
                          relationType: "同盟",
                          description: "",
                          sinceChapterNo: String(projection?.cutoff.chapterNo || 1),
                        });
                      }}
                    >
                      <Link2 className="size-3.5" />
                      连线建关系
                    </Button>
                    <Button size="xs" variant="outline" onClick={onOpenLorePanel}>
                      设定栏编辑
                    </Button>
                  </div>
                </>
              )}

              {selectedEdge && !editingEdge && (
                <>
                  <div className="space-y-2 rounded-xl border border-border bg-surface-subtle p-3">
                    <div className="flex items-center justify-between">
                      <Badge status={selectedEdge.source === "AI_EXTRACTED" ? "ai" : "success"}>
                        {selectedEdge.source === "AI_EXTRACTED" ? "AI 自动抽取" : "作者确认"}
                      </Badge>
                      {selectedEdge.sinceChapterNo != null && (
                        <span className="font-mono text-[11px] text-muted-foreground">
                          第 {selectedEdge.sinceChapterNo} 章生效
                        </span>
                      )}
                    </div>
                    <div className="text-sm font-semibold text-foreground">
                      {selectedEdge.sourceName} —{" "}
                      <span className="text-primary">{selectedEdge.relationType}</span> →{" "}
                      {selectedEdge.targetName}
                    </div>
                    <p className="text-[11.5px] leading-relaxed text-muted-foreground">
                      {selectedEdge.description || "暂无关系说明"}
                    </p>
                  </div>

                  <div className="flex flex-wrap gap-1.5">
                    {selectedEdge.source === "AI_EXTRACTED" ? (
                      <Button
                        size="xs"
                        loading={savingRelation}
                        onClick={() => void handleConfirmAiEdge(selectedEdge)}
                        className="flex-1"
                      >
                        <UserCheck className="size-3.5" />
                        确认为作者关系
                      </Button>
                    ) : (
                      <Button
                        size="xs"
                        variant="secondary"
                        onClick={() =>
                          setEditingEdge({
                            id: selectedEdge.id,
                            sourceCharacter: selectedEdge.sourceName,
                            targetCharacter: selectedEdge.targetName,
                            relationType: selectedEdge.relationType,
                            description: selectedEdge.description || "",
                            sinceChapterNo:
                              selectedEdge.sinceChapterNo != null
                                ? String(selectedEdge.sinceChapterNo)
                                : "",
                            expectedUpdatedAt: selectedEdge.updatedAt,
                          })
                        }
                        className="flex-1"
                      >
                        <Pencil className="size-3.5" />
                        编辑关系
                      </Button>
                    )}
                    <Button
                      size="xs"
                      variant="ghost"
                      onClick={() => void handleDeleteEdge(selectedEdge)}
                      className="text-status-danger hover:bg-status-danger-bg hover:text-status-danger"
                    >
                      <Trash2 className="size-3.5" />
                      删除
                    </Button>
                  </div>
                </>
              )}

              {selectedEdge && editingEdge && (
                <form onSubmit={handleSaveEdgeEdit} className="space-y-2.5">
                  <div className="space-y-1">
                    <label className="text-[11px] text-muted-foreground">关系类型</label>
                    <Input
                      required
                      value={editingEdge.relationType}
                      onChange={(e) =>
                        setEditingEdge({ ...editingEdge, relationType: e.target.value })
                      }
                    />
                  </div>
                  <div className="space-y-1">
                    <label className="text-[11px] text-muted-foreground">生效章节号</label>
                    <Input
                      type="number"
                      min={0}
                      value={editingEdge.sinceChapterNo}
                      onChange={(e) =>
                        setEditingEdge({ ...editingEdge, sinceChapterNo: e.target.value })
                      }
                    />
                  </div>
                  <div className="space-y-1">
                    <label className="text-[11px] text-muted-foreground">关系说明</label>
                    <textarea
                      rows={3}
                      value={editingEdge.description}
                      onChange={(e) =>
                        setEditingEdge({ ...editingEdge, description: e.target.value })
                      }
                      className="w-full resize-none rounded-lg border border-input bg-background px-2.5 py-1.5 text-xs outline-none focus:border-ring"
                    />
                  </div>
                  <div className="flex gap-1.5">
                    <Button type="submit" size="xs" loading={savingRelation} className="flex-1">
                      保存修改
                    </Button>
                    <Button
                      type="button"
                      size="xs"
                      variant="ghost"
                      onClick={() => setEditingEdge(null)}
                    >
                      取消
                    </Button>
                  </div>
                </form>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Quick Create Character Dialog */}
      {charDraft && (
        <Dialog defaultOpen onOpenChangeComplete={(open) => { if (!open) setCharDraft(null); }}>
          <DialogContent className="max-w-md">
            <form onSubmit={handleSubmitQuickCharacter} className="space-y-3">
              <div className="flex items-center justify-between">
                <DialogTitle className="text-sm font-semibold text-foreground">新建角色</DialogTitle>
                <DialogClose aria-label="关闭" render={<Button size="icon-xs" variant="ghost" />}>
                  <X className="size-3.5" />
                </DialogClose>
              </div>
              <div className="grid grid-cols-3 gap-2">
                <div className="col-span-2 space-y-1">
                  <label className="text-[11px] text-muted-foreground">角色名称</label>
                  <Input
                    required
                    value={charDraft.name}
                    onChange={(e) => setCharDraft({ ...charDraft, name: e.target.value })}
                    placeholder="例如：李火旺"
                  />
                </div>
                <Select
                  id="graph-character-role"
                  label="定位"
                  value={charDraft.roleType}
                  onValueChange={(roleType) => { if (roleType) setCharDraft({ ...charDraft, roleType }); }}
                  options={[
                    { value: "PROTAGONIST", label: "主角" },
                    { value: "SUPPORTING", label: "配角" },
                    { value: "ANTAGONIST", label: "反派" },
                  ]}
                />
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div className="space-y-1">
                  <label className="text-[11px] text-muted-foreground">所属势力（可选）</label>
                  <Input
                    value={charDraft.faction}
                    onChange={(e) => setCharDraft({ ...charDraft, faction: e.target.value })}
                    placeholder="例如：清风观"
                  />
                </div>
                <div className="space-y-1">
                  <label className="text-[11px] text-muted-foreground">境界 / 身份（可选）</label>
                  <Input
                    value={charDraft.realmOrLevel}
                    onChange={(e) => setCharDraft({ ...charDraft, realmOrLevel: e.target.value })}
                    placeholder="例如：心素 / 筑基"
                  />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div className="space-y-1">
                  <label className="text-[11px] text-muted-foreground">别名 / 称谓（逗号分隔）</label>
                  <Input
                    value={charDraft.aliases}
                    onChange={(e) => setCharDraft({ ...charDraft, aliases: e.target.value })}
                    placeholder="例如：火旺, 小道爷"
                  />
                </div>
                <div className="space-y-1">
                  <label className="text-[11px] text-muted-foreground">所在位置（可选）</label>
                  <Input
                    value={charDraft.currentLocation}
                    onChange={(e) => setCharDraft({ ...charDraft, currentLocation: e.target.value })}
                    placeholder="例如：戒律堂地牢"
                  />
                </div>
              </div>
              <div className="flex justify-end gap-2 pt-1">
                <DialogClose render={<Button size="sm" variant="ghost" />}>
                  取消
                </DialogClose>
                <Button type="submit" size="sm" loading={savingChar}>
                  创建角色
                </Button>
              </div>
            </form>
          </DialogContent>
        </Dialog>
      )}

      {/* Drag-to-Connect / New Relation Dialog */}
      {connectDraft && (
        <Dialog defaultOpen onOpenChangeComplete={(open) => { if (!open) setConnectDraft(null); }}>
          <DialogContent className="max-w-md">
            <form onSubmit={handleSubmitNewRelation} className="space-y-3">
              <div className="flex items-center justify-between">
                <DialogTitle className="text-sm font-semibold text-foreground">新增人物关系</DialogTitle>
                <DialogClose aria-label="关闭" render={<Button size="icon-xs" variant="ghost" />}>
                  <X className="size-3.5" />
                </DialogClose>
              </div>

              <div className="grid grid-cols-2 gap-2">
                <div className="space-y-1">
                  <label className="text-[11px] text-muted-foreground">角色 A（源）</label>
                  <Input
                    required
                    list="foliarc-graph-char-names"
                    value={connectDraft.sourceName}
                    onChange={(e) =>
                      setConnectDraft({ ...connectDraft, sourceName: e.target.value })
                    }
                    placeholder="选择或输入角色"
                  />
                </div>
                <div className="space-y-1">
                  <label className="text-[11px] text-muted-foreground">角色 B（目标）</label>
                  <Input
                    required
                    list="foliarc-graph-char-names"
                    value={connectDraft.targetName}
                    onChange={(e) =>
                      setConnectDraft({ ...connectDraft, targetName: e.target.value })
                    }
                    placeholder="选择或输入角色"
                  />
                </div>
                <datalist id="foliarc-graph-char-names">
                  {(projection?.nodes ?? []).map((n) => (
                    <option key={n.id} value={n.name} />
                  ))}
                </datalist>
              </div>

              <div className="space-y-1.5">
                <label className="text-[11px] text-muted-foreground">关系类型</label>
                <div className="flex flex-wrap gap-1">
                  {RELATION_PRESETS.map((preset) => (
                    <button
                      key={preset}
                      type="button"
                      onClick={() => setConnectDraft({ ...connectDraft, relationType: preset })}
                      className={`rounded-full px-2.5 py-0.5 text-[11px] font-medium transition-colors ${
                        connectDraft.relationType === preset
                          ? "bg-primary text-primary-foreground"
                          : "bg-muted text-muted-foreground hover:text-foreground"
                      }`}
                    >
                      {preset}
                    </button>
                  ))}
                </div>
                <Input
                  required
                  value={connectDraft.relationType}
                  onChange={(e) =>
                    setConnectDraft({ ...connectDraft, relationType: e.target.value })
                  }
                  placeholder="自定义关系标签（如：同门宿敌）"
                />
              </div>

              <div className="grid grid-cols-3 gap-2">
                <div className="space-y-1">
                  <label className="text-[11px] text-muted-foreground">生效章节号</label>
                  <Input
                    type="number"
                    min={0}
                    value={connectDraft.sinceChapterNo}
                    onChange={(e) =>
                      setConnectDraft({ ...connectDraft, sinceChapterNo: e.target.value })
                    }
                  />
                </div>
                <div className="col-span-2 space-y-1">
                  <label className="text-[11px] text-muted-foreground">关系说明（可选）</label>
                  <Input
                    value={connectDraft.description}
                    onChange={(e) =>
                      setConnectDraft({ ...connectDraft, description: e.target.value })
                    }
                    placeholder="例如：地牢同行后结下生死契约"
                  />
                </div>
              </div>

              <div className="flex justify-end gap-2 pt-1">
                <DialogClose render={<Button size="sm" variant="ghost" />}>
                  取消
                </DialogClose>
                <Button type="submit" size="sm" loading={savingRelation}>
                  保存关系
                </Button>
              </div>
            </form>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}

export function CharacterRelationGraph(props: CharacterRelationGraphProps) {
  return (
    <ReactFlowProvider>
      <CharacterRelationGraphInner {...props} />
    </ReactFlowProvider>
  );
}
