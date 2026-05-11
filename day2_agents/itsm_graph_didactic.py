from __future__ import annotations

import argparse
import json
import os
import re
import time
from datetime import datetime
from typing import Any, Callable, Dict, List, Literal, Optional, TypedDict

from dotenv import load_dotenv
from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import tool
from langchain_google_genai import ChatGoogleGenerativeAI

"""
day2_agents/itsm_graph_didactic.py

Esercitazione Giorno 2 pomeriggio:
- trasformare il loop ReAct manuale del mattino in un grafo a stati didattico;
- capire nodi, edge, edge condizionali, stato condiviso e stop condition;
- introdurre Human-in-the-loop prima di passare alla versione LangGraph reale.

Questo file NON usa LangGraph: implementa un mini-grafo Python volutamente semplice.
Serve come ponte concettuale:

    loop manuale ReAct  ->  grafo didattico scritto a mano  ->  LangGraph vero

Comandi utili:
    python day2_agents/itsm_graph_didactic.py examples

    python day2_agents/itsm_graph_didactic.py run \
        "Mostrami il record INC-1002 e calcola lo SLA."

    python day2_agents/itsm_graph_didactic.py run \
        "Analizza INC-1002, calcola lo SLA e proponi l'escalation se serve." \
        --auto-decision approve

    python day2_agents/itsm_graph_didactic.py run \
        "Analizza INC-1002, calcola lo SLA e proponi l'escalation se serve." \
        --auto-decision reject

Variabili .env:
    GOOGLE_API_KEY=...
    GEMINI_MODEL=gemini-2.5-flash
    MIN_SECONDS_BETWEEN_MODEL_CALLS=15
    MAX_GRAPH_STEPS=20
    MAX_TOOL_CALLS=8
    MAX_TOTAL_SECONDS=90

Idea didattica centrale:
    Lo stato è il taccuino dell'agente.
    Ogni nodo legge il taccuino, scrive qualcosa e poi il grafo decide il prossimo nodo.
"""


# ---------------------------------------------------------------------
# Configurazione
# ---------------------------------------------------------------------

load_dotenv()

END = "__end__"

GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")
MIN_SECONDS_BETWEEN_MODEL_CALLS = float(
    os.getenv("MIN_SECONDS_BETWEEN_MODEL_CALLS", "15")
)
MAX_GRAPH_STEPS = int(os.getenv("MAX_GRAPH_STEPS", "20"))
MAX_TOOL_CALLS = int(os.getenv("MAX_TOOL_CALLS", "8"))
MAX_TOTAL_SECONDS = float(os.getenv("MAX_TOTAL_SECONDS", "90"))

Decision = Literal["approve", "reject"]


# ---------------------------------------------------------------------
# Stato condiviso del grafo
# ---------------------------------------------------------------------

class AgentState(TypedDict):
    """
    Stato condiviso tra i nodi.

    In LangGraph questo TypedDict diventerà direttamente lo schema dello stato.
    Qui lo usiamo già, così gli studenti vedono subito il pattern corretto.
    """

    messages: List[Any]
    scratchpad: str
    artifacts: List[str]

    pending_tool_calls: List[Dict[str, Any]]
    final_answer: Optional[str]

    thread_id: str
    started_at: float
    last_llm_call_ts: float
    step_count: int
    tool_call_count: int

    ticket: Optional[Dict[str, Any]]
    sla: Optional[Dict[str, Any]]

    risk_level: str
    pending_action: Optional[Dict[str, Any]]
    approval_required: bool
    approved: Optional[bool]
    auto_decision: Optional[Decision]

    traces: List[Dict[str, Any]]


# ---------------------------------------------------------------------
# Mini knowledge base e mini database operativo
# ---------------------------------------------------------------------

SAMPLE_DOCS: Dict[str, str] = {
    "hr_policy": (
        "I dipendenti possono richiedere ferie tramite il portale HR. "
        "Per ticket urgenti serve indicare impatto, urgenza e servizio coinvolto."
    ),
    "itsm_sla_policy": (
        "Un ticket P1 è critico. La presa in carico deve avvenire entro 30 minuti. "
        "Se un P1 non è preso in carico entro 30 minuti, lo SLA è violato e il ticket "
        "deve essere escalato al team on-call."
    ),
    "itsm_escalation_policy": (
        "Le azioni critiche, come aprire una escalation formale, notificare il service manager "
        "o avviare una comunicazione di major incident, richiedono conferma umana esplicita."
    ),
}

SAMPLE_RECORDS: Dict[str, Dict[str, Any]] = {
    "INC-1002": {
        "id": "INC-1002",
        "summary": "Interruzione servizio e-mail",
        "description": "Diversi utenti non riescono ad accedere alla posta aziendale.",
        "priority": "P1",
        "elapsed_hours": 1.0,
        "owner": "team-sre",
        "service": "Corporate Email",
        "environment": "production",
        "affected_users": 180,
        "workaround_available": False,
    },
    "INC-1003": {
        "id": "INC-1003",
        "summary": "Latenza API procurement",
        "description": "Le API di creazione ordine rispondono lentamente.",
        "priority": "P2",
        "elapsed_hours": 3.6,
        "owner": "team-app-procurement",
        "service": "Procurement Platform",
        "environment": "production",
        "affected_users": 35,
        "workaround_available": True,
    },
}


# ---------------------------------------------------------------------
# Utility generali
# ---------------------------------------------------------------------

def to_json(data: Any) -> str:
    return json.dumps(data, ensure_ascii=False, default=str)


def print_json(data: Any) -> None:
    print(json.dumps(data, ensure_ascii=False, indent=2, default=str))


def extract_text_content(content: Any) -> str:
    if isinstance(content, str):
        return content.strip()

    if isinstance(content, list):
        parts: List[str] = []
        for item in content:
            if isinstance(item, dict) and isinstance(item.get("text"), str):
                if item["text"].strip():
                    parts.append(item["text"].strip())
            elif isinstance(item, str) and item.strip():
                parts.append(item.strip())
        return "\n".join(parts).strip()

    return str(content).strip()


def parse_tool_result(raw: str) -> Dict[str, Any]:
    try:
        parsed = json.loads(raw)
        if isinstance(parsed, dict):
            return parsed
        return {"success": True, "results": [parsed], "error": None}
    except Exception:
        return {"success": True, "results": [raw], "error": None}


def append_trace(
    state: AgentState,
    *,
    event: str,
    node: Optional[str] = None,
    tool: Optional[str] = None,
    args: Optional[Dict[str, Any]] = None,
    result: Optional[Any] = None,
    error: Optional[str] = None,
    text: Optional[str] = None,
) -> None:
    state["traces"].append(
        {
            "step": len(state["traces"]) + 1,
            "timestamp": datetime.now().isoformat(timespec="seconds"),
            "event": event,
            "node": node,
            "tool": tool,
            "args": args or {},
            "result": result,
            "error": error,
            "text": text,
        }
    )


# ---------------------------------------------------------------------
# Funzioni di dominio pure: non dipendono da LangChain
# ---------------------------------------------------------------------

def search_kb(query: str, top_k: int = 3) -> Dict[str, Any]:
    """
    Ricerca lessicale minimale.

    Nel codice completo questa funzione può essere sostituita dalla RAG del Giorno 1.
    Qui resta volutamente semplice: serve a mostrare il tool calling, non a insegnare retrieval.
    """
    q = (query or "").lower().strip()

    if not q:
        return {"success": False, "results": [], "error": "query vuota"}

    tokens = [tok for tok in re.findall(r"[a-zA-ZÀ-ÿ0-9]+", q) if len(tok) > 1]
    hits: List[Dict[str, Any]] = []

    for source, text in SAMPLE_DOCS.items():
        score = sum(1 for tok in tokens if tok in text.lower())
        if score > 0:
            hits.append(
                {
                    "source": source,
                    "snippet": text[:260],
                    "score": score,
                }
            )

    hits.sort(key=lambda x: x["score"], reverse=True)

    return {
        "success": bool(hits),
        "results": hits[:top_k],
        "error": None if hits else "Nessun documento pertinente trovato.",
    }


def lookup_record(record_id: str) -> Dict[str, Any]:
    rid = (record_id or "").strip().upper()

    if not rid:
        return {"success": False, "results": [], "error": "record_id vuoto"}

    rec = SAMPLE_RECORDS.get(rid)

    if not rec:
        return {
            "success": False,
            "results": [],
            "error": f"Record {rid} non trovato",
            "meta": {"available_records": sorted(SAMPLE_RECORDS.keys())},
        }

    return {"success": True, "results": [rec], "error": None}


def compute_sla(ticket: Dict[str, Any]) -> Dict[str, Any]:
    priority = str(ticket.get("priority", "P3")).upper().strip()
    elapsed_hours = float(ticket.get("elapsed_hours", 0.0))

    thresholds = {
        "P1": 0.5,
        "P2": 4.0,
        "P3": 24.0,
        "P4": 72.0,
    }

    threshold_hours = thresholds.get(priority, 24.0)
    remaining_hours = round(threshold_hours - elapsed_hours, 3)

    if remaining_hours < 0:
        status = "violated"
        recommendation = "escalate"
        critical = True
        reason = (
            f"SLA violato: priorità {priority}, soglia {threshold_hours}h, "
            f"tempo trascorso {elapsed_hours}h."
        )
    elif remaining_hours <= threshold_hours * 0.2:
        status = "near_breach"
        recommendation = "prepare_escalation"
        critical = priority == "P1"
        reason = (
            f"Ticket vicino alla violazione SLA: rimangono {remaining_hours}h "
            f"su soglia {threshold_hours}h."
        )
    else:
        status = "ok"
        recommendation = "continue"
        critical = False
        reason = (
            f"SLA entro soglia: rimangono {remaining_hours}h "
            f"su soglia {threshold_hours}h."
        )

    result = {
        "id": ticket.get("id"),
        "priority": priority,
        "threshold_hours": threshold_hours,
        "elapsed_hours": elapsed_hours,
        "remaining_hours": remaining_hours,
        "status": status,
        "recommendation": recommendation,
        "critical": critical,
        "reason": reason,
    }

    return {"success": True, "results": [result], "error": None}


def build_pending_action(ticket: Dict[str, Any], sla: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not sla.get("critical"):
        return None

    return {
        "action_type": "open_formal_escalation",
        "ticket_id": ticket.get("id", sla.get("id")),
        "priority": ticket.get("priority", sla.get("priority")),
        "owner": ticket.get("owner", "unknown"),
        "critical": True,
        "reason": sla.get("reason", "Azione critica richiesta dalla policy SLA."),
    }


def execute_critical_action(action: Dict[str, Any], approved: bool) -> Dict[str, Any]:
    if not action:
        return {"success": False, "results": [], "error": "Nessuna azione pendente."}

    if not approved:
        return {
            "success": True,
            "results": [
                {
                    "executed": False,
                    "action": action,
                    "message": "Azione critica non eseguita: approvazione umana negata.",
                }
            ],
            "error": None,
        }

    return {
        "success": True,
        "results": [
            {
                "executed": True,
                "action": action,
                "message": (
                    f"Escalation formale simulata per ticket {action.get('ticket_id')} "
                    f"verso owner {action.get('owner')}."
                ),
            }
        ],
        "error": None,
    }


# ---------------------------------------------------------------------
# Tool LangChain: wrapper intorno alle funzioni di dominio
# ---------------------------------------------------------------------

@tool
def search_kb_tool(query: str, top_k: int = 3) -> str:
    """
    Cerca nella knowledge base ITSM policy su SLA, ticket urgenti, escalation e approvazioni.

    Usalo quando la domanda riguarda regole, policy o documentazione.
    Restituisce source, snippet e score dei documenti più pertinenti.
    """
    return to_json(search_kb(query, top_k))


@tool
def lookup_record_tool(record_id: str) -> str:
    """
    Recupera un record operativo ITSM dato un id, per esempio INC-1002.

    Usalo quando la richiesta cita un ticket o un incidente specifico.
    Restituisce priorità, tempo trascorso, owner, servizio e altri dati operativi.
    """
    return to_json(lookup_record(record_id))


@tool
def compute_sla_tool(
    id: str,
    priority: str,
    elapsed_hours: float,
    owner: str = "unknown",
    summary: str = "",
) -> str:
    """
    Calcola lo stato SLA di un ticket ITSM usando campi strutturati.

    Usalo dopo lookup_record_tool quando devi stabilire se un ticket è:
    - entro SLA;
    - vicino alla violazione;
    - in violazione SLA;
    - candidato a escalation.
    """
    ticket = {
        "id": id,
        "priority": priority,
        "elapsed_hours": elapsed_hours,
        "owner": owner,
        "summary": summary,
    }
    return to_json(compute_sla(ticket))


TOOLS = [search_kb_tool, lookup_record_tool, compute_sla_tool]
TOOL_MAP = {t.name: t for t in TOOLS}


# ---------------------------------------------------------------------
# Prompt di sistema
# ---------------------------------------------------------------------

SYSTEM_PROMPT = """
Sei un agente ITSM enterprise.

Obiettivo:
- aiutare l'operatore a leggere record ITSM, policy e stato SLA;
- usare i tool quando servono dati operativi, documentazione o calcoli;
- non inventare ticket, policy, owner o soglie SLA.

Regole operative:
1. Se la domanda cita un ticket come INC-1002, usa lookup_record_tool.
2. Se devi valutare SLA, usa compute_sla_tool dopo aver recuperato il ticket.
3. Se la domanda riguarda policy, ticket urgenti, P1 o escalation, usa search_kb_tool.
4. Le azioni critiche, come escalation formale o major incident, devono essere proposte e richiedono conferma umana.
5. Rispondi in italiano, in modo operativo e sintetico.

Formato consigliato della risposta finale:
- Sintesi
- Evidenze usate
- Valutazione SLA, se presente
- Raccomandazione
- Conferma umana richiesta, se applicabile
""".strip()


# ---------------------------------------------------------------------
# Mini framework a grafo
# ---------------------------------------------------------------------

class SimpleStateGraph:
    """
    Mini-versione didattica di un grafo a stati.

    Non sostituisce LangGraph. Serve a far vedere il meccanismo essenziale:
    - ogni nodo è una funzione state -> state;
    - un edge fisso collega un nodo al successivo;
    - un edge condizionale decide il prossimo nodo leggendo lo stato.
    """

    def __init__(self) -> None:
        self.nodes: Dict[str, Callable[[AgentState], AgentState]] = {}
        self.edges: Dict[str, str] = {}
        self.conditional_edges: Dict[str, Callable[[AgentState], str]] = {}
        self.entry_point: Optional[str] = None

    def add_node(self, name: str, func: Callable[[AgentState], AgentState]) -> None:
        self.nodes[name] = func

    def set_entry_point(self, name: str) -> None:
        if name not in self.nodes:
            raise ValueError(f"Nodo di ingresso non registrato: {name}")
        self.entry_point = name

    def add_edge(self, source: str, target: str) -> None:
        self.edges[source] = target

    def add_conditional_edges(self, source: str, condition: Callable[[AgentState], str]) -> None:
        self.conditional_edges[source] = condition

    def invoke(self, state: AgentState) -> AgentState:
        if not self.entry_point:
            raise RuntimeError("Entry point non definito")

        current = self.entry_point

        while current != END:
            if current not in self.nodes:
                raise RuntimeError(f"Nodo non registrato: {current}")

            if time.time() - state["started_at"] > MAX_TOTAL_SECONDS:
                state["final_answer"] = f"Timeout grafo dopo {MAX_TOTAL_SECONDS}s."
                append_trace(state, event="stop", node=current, error=state["final_answer"])
                break

            if state["step_count"] >= MAX_GRAPH_STEPS:
                state["final_answer"] = f"Stop: superato MAX_GRAPH_STEPS={MAX_GRAPH_STEPS}."
                append_trace(state, event="stop", node=current, error=state["final_answer"])
                break

            state["step_count"] += 1
            append_trace(state, event="enter_node", node=current)
            state = self.nodes[current](state)

            if current in self.conditional_edges:
                current = self.conditional_edges[current](state)
            else:
                current = self.edges.get(current, END)

            append_trace(state, event="route", node=current)

        return state


# ---------------------------------------------------------------------
# Nodi del grafo
# ---------------------------------------------------------------------

def llm_node(state: AgentState) -> AgentState:
    """
    Nodo Reason.

    Il modello legge i messaggi e decide se:
    - rispondere direttamente;
    - chiedere uno o più tool call.
    """
    if not os.getenv("GOOGLE_API_KEY"):
        state["final_answer"] = "GOOGLE_API_KEY non configurata."
        append_trace(state, event="configuration_error", node="llm", error=state["final_answer"])
        return state

    now = time.time()
    wait_s = MIN_SECONDS_BETWEEN_MODEL_CALLS - (now - state["last_llm_call_ts"])

    if wait_s > 0:
        time.sleep(wait_s)

    llm = ChatGoogleGenerativeAI(
        model=GEMINI_MODEL,
        temperature=0,
        timeout=25,
    )
    llm_with_tools = llm.bind_tools(TOOLS)

    ai_msg = llm_with_tools.invoke(state["messages"])
    state["last_llm_call_ts"] = time.time()
    state["messages"].append(ai_msg)

    tool_calls = getattr(ai_msg, "tool_calls", None) or []
    text = extract_text_content(getattr(ai_msg, "content", ""))

    state["pending_tool_calls"] = tool_calls
    state["final_answer"] = text if text and not tool_calls else None

    append_trace(
        state,
        event="llm_response",
        node="llm",
        text=text if text else "[tool-call only]",
        result={
            "tool_calls": [
                {
                    "id": tc.get("id"),
                    "name": tc.get("name"),
                    "args": tc.get("args", {}),
                }
                for tc in tool_calls
            ]
        },
    )

    return state


def route_after_llm(state: AgentState) -> str:
    """
    Edge condizionale dopo il nodo LLM.
    """
    if state.get("final_answer"):
        return END

    if state.get("pending_tool_calls"):
        return "tool_executor"

    return END


def tool_executor_node(state: AgentState) -> AgentState:
    """
    Nodo Act + Observe.

    Esegue i tool richiesti dal modello e reinserisce il risultato nei messaggi.
    """
    tool_calls = state.get("pending_tool_calls") or []

    if not tool_calls:
        return state

    for tc in tool_calls:
        if state["tool_call_count"] >= MAX_TOOL_CALLS:
            state["final_answer"] = f"Stop: superato MAX_TOOL_CALLS={MAX_TOOL_CALLS}."
            append_trace(state, event="stop", node="tool_executor", error=state["final_answer"])
            return state

        name = tc.get("name", "")
        args = tc.get("args", {}) or {}
        tool_call_id = tc.get("id", f"tool-call-{state['tool_call_count'] + 1}")

        if name not in TOOL_MAP:
            raw_result = to_json({"success": False, "results": [], "error": f"Tool sconosciuto: {name}"})
        else:
            try:
                raw_result = TOOL_MAP[name].invoke(args)
            except Exception as exc:
                raw_result = to_json(
                    {
                        "success": False,
                        "results": [],
                        "error": f"Errore durante l'esecuzione del tool {name}: {exc}",
                    }
                )

        state["tool_call_count"] += 1
        parsed = parse_tool_result(raw_result)

        state["messages"].append(
            ToolMessage(
                content=raw_result,
                name=name,
                tool_call_id=tool_call_id,
            )
        )

        state["scratchpad"] += f"\n[tool:{name}] {raw_result}"
        state["artifacts"].append(f"{name} result")

        if name == "lookup_record_tool" and parsed.get("success") and parsed.get("results"):
            state["ticket"] = parsed["results"][0]

        if name == "compute_sla_tool" and parsed.get("success") and parsed.get("results"):
            state["sla"] = parsed["results"][0]

        append_trace(
            state,
            event="tool_call",
            node="tool_executor",
            tool=name,
            args=args,
            result=parsed,
        )

    state["pending_tool_calls"] = []
    return state


def route_after_tools(state: AgentState) -> str:
    if state.get("final_answer"):
        return END
    return "risk_check"


def risk_check_node(state: AgentState) -> AgentState:
    """
    Nodo di controllo rischio.

    Qui separiamo il calcolo tecnico dello SLA dalla decisione di governance.
    Il tool calcola; il grafo decide se serve approvazione umana.
    """
    ticket = state.get("ticket")
    sla = state.get("sla")

    if not ticket or not sla:
        state["risk_level"] = "low"
        append_trace(state, event="risk_check", node="risk_check", result={"risk_level": "low"})
        return state

    pending_action = build_pending_action(ticket, sla)

    if pending_action:
        state["risk_level"] = "high"
        state["pending_action"] = pending_action
        state["approval_required"] = True
        append_trace(
            state,
            event="critical_action_detected",
            node="risk_check",
            result=pending_action,
        )
    else:
        state["risk_level"] = "low"
        append_trace(
            state,
            event="risk_check",
            node="risk_check",
            result={"risk_level": "low", "pending_action": None},
        )

    return state


def route_after_risk_check(state: AgentState) -> str:
    if state.get("pending_action") and state.get("approved") is None:
        return "human_review"
    return "llm"


def human_review_node(state: AgentState) -> AgentState:
    """
    Nodo Human-in-the-loop.

    In un'app reale qui il grafo si sospenderebbe e attenderebbe un operatore.
    In questa demo CLI usiamo --auto-decision approve/reject.
    """
    decision = state.get("auto_decision")

    if decision is None:
        state["approval_required"] = True
        state["final_answer"] = (
            "Serve approvazione umana per procedere con l'azione critica:\n"
            f"{to_json(state.get('pending_action'))}\n\n"
            "Rilancia il comando con --auto-decision approve oppure --auto-decision reject."
        )
        append_trace(
            state,
            event="human_approval_required",
            node="human_review",
            result=state.get("pending_action"),
        )
        return state

    approved = decision == "approve"
    state["approved"] = approved
    state["approval_required"] = False

    append_trace(
        state,
        event="human_approval",
        node="human_review",
        result={"decision": decision, "approved": approved},
    )

    return state


def route_after_human_review(state: AgentState) -> str:
    if state.get("final_answer"):
        return END
    return "execute_action"


def execute_action_node(state: AgentState) -> AgentState:
    """
    Nodo che simula l'esecuzione di una azione critica dopo approvazione umana.
    """
    execution = execute_critical_action(
        action=state.get("pending_action") or {},
        approved=bool(state.get("approved")),
    )

    append_trace(
        state,
        event="critical_action_execution",
        node="execute_action",
        result=execution,
    )

    state["messages"].append(
        HumanMessage(
            content=(
                "Esito del controllo umano e dell'azione critica:\n"
                f"{to_json(execution)}\n\n"
                "Ora produci una risposta finale per l'operatore. "
                "Indica evidenze, stato SLA, azione approvata o non approvata."
            )
        )
    )

    return state


# ---------------------------------------------------------------------
# Costruzione del grafo
# ---------------------------------------------------------------------

def build_graph() -> SimpleStateGraph:
    """
    Grafo didattico:

        llm
         |
         |-- se risposta finale ------------> END
         |
         |-- se tool_calls -----------------> tool_executor
                                             |
                                             v
                                         risk_check
                                             |
                                             |-- se azione critica --> human_review
                                             |                         |
                                             |                         v
                                             |                    execute_action
                                             |                         |
                                             --------------------------> llm

    Differenza importante rispetto al loop manuale:
    il comportamento non è più un unico while monolitico; è distribuito in nodi.
    """
    g = SimpleStateGraph()

    g.add_node("llm", llm_node)
    g.add_node("tool_executor", tool_executor_node)
    g.add_node("risk_check", risk_check_node)
    g.add_node("human_review", human_review_node)
    g.add_node("execute_action", execute_action_node)

    g.set_entry_point("llm")

    g.add_conditional_edges("llm", route_after_llm)
    g.add_conditional_edges("tool_executor", route_after_tools)
    g.add_conditional_edges("risk_check", route_after_risk_check)
    g.add_conditional_edges("human_review", route_after_human_review)
    g.add_edge("execute_action", "llm")

    return g


# ---------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------

def build_initial_state(
    *,
    thread_id: str,
    prompt: str,
    auto_decision: Optional[Decision] = None,
) -> AgentState:
    return {
        "messages": [
            SystemMessage(content=SYSTEM_PROMPT),
            HumanMessage(content=prompt),
        ],
        "scratchpad": "",
        "artifacts": [],
        "pending_tool_calls": [],
        "final_answer": None,
        "thread_id": thread_id,
        "started_at": time.time(),
        "last_llm_call_ts": 0.0,
        "step_count": 0,
        "tool_call_count": 0,
        "ticket": None,
        "sla": None,
        "risk_level": "low",
        "pending_action": None,
        "approval_required": False,
        "approved": None,
        "auto_decision": auto_decision,
        "traces": [],
    }


def run_conversation(
    thread_id: str,
    prompt: str,
    auto_decision: Optional[Decision] = None,
) -> AgentState:
    state = build_initial_state(
        thread_id=thread_id,
        prompt=prompt,
        auto_decision=auto_decision,
    )
    graph = build_graph()
    return graph.invoke(state)


# ---------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------

EXAMPLE_PROMPTS: List[Dict[str, str]] = [
    {
        "title": "1. Record + SLA",
        "prompt": "Mostrami il record INC-1002 e calcola lo SLA.",
        "expected": "lookup_record_tool -> compute_sla_tool -> risk_check -> eventuale HITL",
    },
    {
        "title": "2. Ricerca policy",
        "prompt": "Cerco la policy per i ticket urgenti e per l'escalation P1.",
        "expected": "search_kb_tool -> risposta con fonti",
    },
    {
        "title": "3. Record inesistente",
        "prompt": "Recupera INC-9999 e dimmi lo stato SLA.",
        "expected": "lookup_record_tool -> gestione errore senza inventare dati",
    },
    {
        "title": "4. HITL approvato",
        "prompt": "Analizza INC-1002, calcola lo SLA e proponi l'escalation se serve.",
        "expected": "lookup_record_tool -> compute_sla_tool -> human_review -> execute_action -> risposta finale",
    },
]


def cmd_examples(_: argparse.Namespace) -> None:
    for item in EXAMPLE_PROMPTS:
        print("=" * 100)
        print(item["title"])
        print(f"Prompt: {item['prompt']}")
        print(f"Flusso atteso: {item['expected']}")


def cmd_run(args: argparse.Namespace) -> None:
    result = run_conversation(
        thread_id=args.thread_id,
        prompt=args.prompt,
        auto_decision=args.auto_decision,
    )

    print("\nFINAL ANSWER")
    print("=" * 100)
    print(result.get("final_answer") or "[Nessuna risposta finale prodotta]")

    print("\nSTATE SUMMARY")
    print("=" * 100)
    print_json(
        {
            "thread_id": result.get("thread_id"),
            "step_count": result.get("step_count"),
            "tool_call_count": result.get("tool_call_count"),
            "ticket": result.get("ticket"),
            "sla": result.get("sla"),
            "risk_level": result.get("risk_level"),
            "pending_action": result.get("pending_action"),
            "approval_required": result.get("approval_required"),
            "approved": result.get("approved"),
            "artifacts": result.get("artifacts"),
        }
    )

    print("\nTRACE")
    print("=" * 100)
    print_json(result.get("traces", []))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Esercitazione Giorno 2 pomeriggio: mini-grafo didattico per agent ITSM "
            "con tool calling, stato condiviso, routing condizionale e HITL."
        )
    )

    subparsers = parser.add_subparsers(required=True)

    examples_parser = subparsers.add_parser("examples")
    examples_parser.set_defaults(func=cmd_examples)

    run_parser = subparsers.add_parser("run")
    run_parser.add_argument("prompt", type=str)
    run_parser.add_argument("--thread-id", type=str, default="demo-graph-001")
    run_parser.add_argument(
        "--auto-decision",
        choices=["approve", "reject"],
        default=None,
        help="Simula la decisione umana sulle azioni critiche.",
    )
    run_parser.set_defaults(func=cmd_run)

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
