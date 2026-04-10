const PIECES = {
    K: { glyph: "♔", color: "white" },
    Q: { glyph: "♕", color: "white" },
    R: { glyph: "♖", color: "white" },
    B: { glyph: "♗", color: "white" },
    N: { glyph: "♘", color: "white" },
    P: { glyph: "♙", color: "white" },
    k: { glyph: "♚", color: "black" },
    q: { glyph: "♛", color: "black" },
    r: { glyph: "♜", color: "black" },
    b: { glyph: "♝", color: "black" },
    n: { glyph: "♞", color: "black" },
    p: { glyph: "♟", color: "black" }
};

const UCI_MOVE_PATTERN = /\b[a-h][1-8][a-h][1-8][qrbn]?\b/gi;

const state = {
    sessionId: window.sessionStorage.getItem("agentic-chess-session"),
    snapshot: null,
    previousSnapshot: null,
    selectedSquare: null,
    hoverSquare: null,
    candidateHistory: [],
    commentaryPhase: "",
    commentaryStream: "",
    scrollCandidateHistoryToTop: false,
    socket: null,
    reconnectTimer: null,
    reconnecting: false,
    requestInFlight: false,
    uiMode: "starting"
};

const elements = {
    board: document.getElementById("board"),
    boardAnimationLayer: document.getElementById("board-animation-layer"),
    boardCard: document.getElementById("board-card"),
    candidateLines: document.getElementById("candidate-lines"),
    commentaryList: document.getElementById("commentary-list"),
    gameStatusText: document.getElementById("game-status-text"),
    hintText: document.getElementById("hint-text"),
    newGameButton: document.getElementById("new-game-button"),
    resetGameButton: document.getElementById("reset-game-button"),
    sessionText: document.getElementById("session-text"),
    sidebar: document.getElementById("sidebar"),
    statusPill: document.getElementById("status-pill"),
    turnText: document.getElementById("turn-text"),
    candidateTemplate: document.getElementById("candidate-template")
};

elements.newGameButton.addEventListener("click", createGame);
elements.resetGameButton.addEventListener("click", resetGame);
elements.board.addEventListener("mouseleave", () => {
    if (state.selectedSquare || !state.hoverSquare) {
        return;
    }
    state.hoverSquare = null;
    renderBoard();
});

if (typeof ResizeObserver === "function") {
    new ResizeObserver(syncSidebarHeight).observe(elements.boardCard);
}
window.addEventListener("resize", syncSidebarHeight);

render();
void bootstrapGame();

async function bootstrapGame() {
    if (state.sessionId) {
        try {
            const snapshot = await loadSnapshot(state.sessionId);
            if (snapshot) {
                state.uiMode = null;
                updateSnapshot(snapshot);
                connectSocket();
                return;
            }
        } catch (error) {
            console.error("Failed to restore chess session", error);
        }
        clearSession();
        state.uiMode = "starting";
        render();
    }

    await createGame({
        pendingMode: "starting",
        failureHint: "Unable to start a game automatically. Use New Game to retry."
    });
}

async function createGame(options = {}) {
    if (state.requestInFlight) {
        return;
    }

    state.requestInFlight = true;
    state.uiMode = options.pendingMode || "starting";
    state.reconnecting = false;
    render();

    try {
        const snapshot = await requestJson("POST", "/chess/api/game");
        state.requestInFlight = false;
        state.uiMode = null;
        state.selectedSquare = null;
        updateSnapshot(snapshot);
        persistSession(snapshot.sessionId);
        connectSocket();
    } catch (error) {
        console.error("Failed to create chess game", error);
        state.requestInFlight = false;
        state.uiMode = null;
        clearSession();
        render();
        elements.hintText.textContent = options.failureHint || "Unable to start a new game. Try again.";
        setStatus("Unavailable");
    }
}

async function resetGame() {
    if (!state.sessionId || state.requestInFlight) {
        return;
    }

    state.requestInFlight = true;
    state.uiMode = "resetting";
    state.reconnecting = false;
    render();

    try {
        const snapshot = await requestJson("POST", `/chess/api/game/${state.sessionId}/reset`);
        state.requestInFlight = false;
        state.uiMode = null;
        state.selectedSquare = null;
        updateSnapshot(snapshot);
        connectSocket();
    } catch (error) {
        console.error("Failed to reset chess game", error);
        state.requestInFlight = false;
        state.uiMode = null;
        render();
        elements.hintText.textContent = "Unable to reset the game. Try again.";
        setStatus("Unavailable");
    }
}

async function submitMove(move) {
    if (!state.sessionId) {
        return;
    }
    const result = await requestJson("POST",
                                     `/chess/api/game/${state.sessionId}/move`,
                                     { move });
    if (!result.accepted) {
        elements.hintText.textContent = result.message || "Move rejected.";
    }
}

async function loadSnapshot(sessionId) {
    const response = await fetch(`/chess/api/game/${sessionId}`);
    if (response.status === 404) {
        return null;
    }
    if (!response.ok) {
        throw new Error(`Failed to load snapshot: ${response.status}`);
    }
    return response.json();
}

async function requestJson(method, path, body) {
    const response = await fetch(path, {
        method,
        headers: {
            "Accept": "application/json",
            "Content-Type": "application/json"
        },
        body: body ? JSON.stringify(body) : undefined
    });
    const payload = await response.json();
    if (!response.ok) {
        throw new Error(payload.message || `Request failed with status ${response.status}`);
    }
    return payload;
}

function connectSocket() {
    if (!state.sessionId) {
        return;
    }
    clearTimeout(state.reconnectTimer);
    if (state.socket) {
        const previousSocket = state.socket;
        state.socket = null;
        previousSocket.close();
    }
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const socket = new WebSocket(`${protocol}//${window.location.host}/chess/events/${state.sessionId}`);
    state.socket = socket;

    socket.addEventListener("open", () => {
        if (state.socket !== socket) {
            return;
        }
        state.reconnecting = false;
        renderMeta();
        socket.send("resync");
    });

    socket.addEventListener("message", event => {
        const payload = JSON.parse(event.data);
        handleSocketEvent(payload);
    });

    socket.addEventListener("close", () => {
        if (state.socket !== socket) {
            return;
        }

        state.socket = null;
        if (state.sessionId) {
            state.reconnecting = true;
            renderMeta();
            clearTimeout(state.reconnectTimer);
            state.reconnectTimer = window.setTimeout(connectSocket, 1500);
        }
    });
}

function handleSocketEvent(event) {
    if (event.snapshot) {
        updateSnapshot(event.snapshot);
    }

    switch (event.type) {
        case "awaiting-move":
            elements.hintText.textContent = "Hover a white piece to inspect legal destinations, then click a target square.";
            break;
        case "human-move-rejected":
            elements.hintText.textContent = event.message || "Move rejected.";
            break;
        case "human-move-accepted":
            elements.hintText.textContent = "Human move applied. Commentary starts while Black prepares a reply.";
            state.selectedSquare = null;
            break;
        case "ai-thinking":
            elements.hintText.textContent = "Black is thinking through the position.";
            break;
        case "commentary-start":
            state.commentaryPhase = event.message || event.snapshot?.commentaryPhase || "";
            state.commentaryStream = event.snapshot?.streamingCommentary || "";
            renderCommentary();
            break;
        case "commentary-chunk":
            state.commentaryStream += event.text || "";
            renderCommentary();
            break;
        case "commentary-finished":
            state.commentaryStream = "";
            state.commentaryPhase = "";
            renderCommentary();
            break;
        case "game-over":
            elements.hintText.textContent = event.message || "Game over.";
            break;
        case "error":
            elements.hintText.textContent = event.message || "An error occurred.";
            break;
        default:
            break;
    }

    renderMeta();
    syncSidebarHeight();
}

function updateSnapshot(snapshot) {
    const previousSnapshot = state.snapshot;
    state.previousSnapshot = previousSnapshot;
    state.snapshot = snapshot;
    state.commentaryPhase = snapshot?.commentaryPhase || "";
    state.commentaryStream = snapshot?.commentaryStreaming ? (snapshot.streamingCommentary || "") : "";
    syncCandidateHistory(previousSnapshot, snapshot);
    if (snapshot?.sessionId) {
        persistSession(snapshot.sessionId);
    }
    render();
    animateLastMove();
}

function render() {
    renderBoard();
    renderCommentary();
    renderCandidateLines();
    renderMeta();
    window.requestAnimationFrame(syncSidebarHeight);
}

function renderBoard() {
    const board = elements.board;
    board.textContent = "";
    if (!state.snapshot) {
        const placeholder = document.createElement("div");
        placeholder.className = "placeholder";
        placeholder.textContent = state.uiMode === "starting"
                ? "Preparing the board for a new game."
                : "No active game. Use New Game to retry.";
        board.appendChild(placeholder);
        return;
    }

    const pieceMap = fenToPieceMap(state.snapshot.fen);
    const movesByFrom = legalMovesByFrom();
    const activeSquare = state.selectedSquare || state.hoverSquare;
    const activeTargets = new Map((movesByFrom.get(activeSquare) || []).map(move => [move.to, move]));

    for (let rank = 7; rank >= 0; rank--) {
        for (let file = 0; file < 8; file++) {
            const square = `${String.fromCharCode(97 + file)}${rank + 1}`;
            const squareElement = document.createElement("button");
            squareElement.type = "button";
            squareElement.className = `square ${(rank + file) % 2 === 0 ? "light" : "dark"}`;
            squareElement.dataset.square = square;
            if (file === 0) {
                squareElement.classList.add("rank-label");
                squareElement.dataset.rankLabel = String(rank + 1);
            }
            if (rank === 0) {
                squareElement.classList.add("file-label");
                squareElement.dataset.fileLabel = String.fromCharCode(97 + file);
            }

            const piece = pieceMap.get(square);
            const fromMoves = movesByFrom.get(square) || [];
            if (state.snapshot.awaitingHumanMove && fromMoves.length > 0) {
                squareElement.classList.add("clickable");
                squareElement.addEventListener("mouseenter", () => {
                    if (!state.selectedSquare && state.hoverSquare !== square) {
                        state.hoverSquare = square;
                        renderBoard();
                    }
                });
            }

            if (square === state.selectedSquare) {
                squareElement.classList.add("selected");
            }

            if (activeTargets.has(square)) {
                const move = activeTargets.get(square);
                const capture = pieceMap.has(move.to);
                squareElement.classList.add(capture ? "capture-target" : "target");
            }

            squareElement.addEventListener("click", async () => {
                if (!state.snapshot?.awaitingHumanMove) {
                    return;
                }
                const activeSourceSquare = state.selectedSquare || state.hoverSquare;
                if (activeSourceSquare && square !== activeSourceSquare && activeTargets.has(square)) {
                    const move = activeTargets.get(square);
                    state.selectedSquare = null;
                    state.hoverSquare = null;
                    renderBoard();
                    await submitMove(move.move);
                    return;
                }
                if (fromMoves.length > 0) {
                    state.selectedSquare = state.selectedSquare === square ? null : square;
                    state.hoverSquare = square;
                    renderBoard();
                    return;
                }
                state.selectedSquare = null;
                state.hoverSquare = null;
                renderBoard();
            });

            if (piece) {
                const pieceElement = document.createElement("span");
                pieceElement.className = `piece ${piece.color}`;
                pieceElement.textContent = piece.glyph;
                squareElement.appendChild(pieceElement);
            }

            board.appendChild(squareElement);
        }
    }
}

function renderCommentary() {
    const commentaryList = elements.commentaryList;
    commentaryList.textContent = "";

    const latestEntry = state.snapshot?.commentary?.at(-1) || null;
    const activeText = state.commentaryStream.trim();
    const commentaryStreaming = Boolean(state.snapshot?.commentaryStreaming);
    const activePhase = commentaryStreaming
            ? (state.commentaryPhase || "Live commentary")
            : (latestEntry?.phase || "Latest commentary");
    const displayedText = activeText || latestEntry?.text || "";

    if (!displayedText) {
        if (commentaryStreaming) {
            const article = document.createElement("article");
            article.className = "commentary-entry latest-entry";
            const phase = document.createElement("div");
            phase.className = "commentary-phase";
            phase.textContent = activePhase;
            const text = document.createElement("div");
            text.className = "commentary-text";
            text.textContent = "Commentary is starting...";
            article.append(phase, text);
            commentaryList.appendChild(article);
            return;
        }
        commentaryList.appendChild(placeholder("Commentary will appear after the first move."));
        return;
    }

    const article = document.createElement("article");
    article.className = "commentary-entry latest-entry";
    const phase = document.createElement("div");
    phase.className = "commentary-phase";
    phase.textContent = activePhase;
    const text = document.createElement("div");
    text.className = "commentary-text";
    appendHighlightedMoveText(text, displayedText);
    article.append(phase, text);
    commentaryList.appendChild(article);
}

function renderCandidateLines() {
    const container = elements.candidateLines;
    container.textContent = "";
    if (!state.candidateHistory.length) {
        container.appendChild(placeholder("Black's top candidate lines will appear after its move."));
        return;
    }

    for (const batch of state.candidateHistory) {
        const group = document.createElement("section");
        group.className = "candidate-group";

        const heading = document.createElement("div");
        heading.className = "candidate-group-heading";
        heading.textContent = `Black ${batch.moveNumber}... ${batch.move}`;
        group.appendChild(heading);

        for (const line of batch.lines) {
            const fragment = elements.candidateTemplate.content.cloneNode(true);
            const card = fragment.querySelector(".candidate-card");
            const board = fragment.querySelector(".candidate-board");
            const summary = fragment.querySelector(".candidate-summary");
            const moves = fragment.querySelector(".candidate-moves");
            renderMiniBoard(board, line.finalFen);
            summary.textContent = line.summary || "Candidate line";
            moves.textContent = line.moves.join("  ");
            group.append(card);
        }

        container.appendChild(group);
    }

    if (state.scrollCandidateHistoryToTop) {
        container.scrollTop = 0;
        state.scrollCandidateHistoryToTop = false;
    }
}

function renderMiniBoard(boardElement, fen) {
    boardElement.textContent = "";
    const pieceMap = fenToPieceMap(fen);
    for (let rank = 7; rank >= 0; rank--) {
        for (let file = 0; file < 8; file++) {
            const square = `${String.fromCharCode(97 + file)}${rank + 1}`;
            const squareElement = document.createElement("div");
            squareElement.className = `square ${(rank + file) % 2 === 0 ? "light" : "dark"}`;
            const piece = pieceMap.get(square);
            if (piece) {
                const pieceElement = document.createElement("span");
                pieceElement.className = `piece ${piece.color}`;
                pieceElement.textContent = piece.glyph;
                squareElement.appendChild(pieceElement);
            }
            boardElement.appendChild(squareElement);
        }
    }
}

function renderMeta() {
    const snapshot = state.snapshot;
    const hasGame = Boolean(snapshot);
    elements.newGameButton.disabled = state.requestInFlight || state.uiMode === "starting";
    elements.resetGameButton.disabled = !hasGame || state.requestInFlight || state.uiMode === "starting";
    elements.sessionText.textContent = hasGame ? snapshot.sessionId : "No active game";
    elements.turnText.textContent = hasGame ? snapshot.turn : "White";

    if (state.uiMode === "starting") {
        setStatus("Starting", true);
        elements.gameStatusText.textContent = "";
        if (!hasGame) {
            elements.hintText.textContent = "Preparing a new game for White.";
        }
        return;
    }

    if (state.uiMode === "resetting") {
        setStatus("Resetting", true);
        elements.gameStatusText.textContent = "";
        elements.hintText.textContent = "Resetting the board for a fresh game.";
        return;
    }

    if (!hasGame) {
        setStatus("Idle");
        elements.gameStatusText.textContent = "";
        return;
    }

    if (state.reconnecting) {
        setStatus("Reconnecting", true);
    } else if (snapshot.awaitingHumanMove) {
        setStatus("Your move", snapshot.commentaryStreaming);
    } else if (snapshot.aiThinking) {
        setStatus("Black thinking", true);
    } else if (snapshot.commentaryStreaming || state.commentaryStream) {
        setStatus("Streaming", true);
    } else if (snapshot.status !== "ACTIVE") {
        setStatus(snapshot.status);
    } else {
        setStatus("Live");
    }

    elements.gameStatusText.textContent = snapshot.terminalMessage || "";
    if (!snapshot.awaitingHumanMove
            && snapshot.status === "ACTIVE"
            && snapshot.aiThinking
            && (snapshot.commentaryStreaming || state.commentaryStream)) {
        elements.hintText.textContent = "Black is evaluating candidate lines while commentary for White's move is still streaming.";
    } else if (!snapshot.awaitingHumanMove && snapshot.status === "ACTIVE" && snapshot.aiThinking) {
        elements.hintText.textContent = "Black is evaluating candidate lines and preparing a reply.";
    } else if (!snapshot.awaitingHumanMove
            && snapshot.status === "ACTIVE"
            && (snapshot.commentaryStreaming || state.commentaryStream)) {
        elements.hintText.textContent = "Black has moved. Commentary is still streaming.";
    }
}

function syncCandidateHistory(previousSnapshot, snapshot) {
    if (!snapshot) {
        state.candidateHistory = [];
        state.scrollCandidateHistoryToTop = false;
        return;
    }

    const previousMoveCount = previousSnapshot?.moveHistory?.length || 0;
    const currentMoveCount = snapshot.moveHistory?.length || 0;
    if (!previousSnapshot
            || previousSnapshot.sessionId !== snapshot.sessionId
            || currentMoveCount < previousMoveCount) {
        state.candidateHistory = [];
    }

    const latestMove = snapshot.moveHistory?.at(-1);
    if (!latestMove || latestMove.side !== "BLACK" || !snapshot.candidateLines?.length) {
        return;
    }

    const entryKey = `${snapshot.sessionId}:${latestMove.ply}:${snapshot.lastMove}`;
    if (state.candidateHistory.some(entry => entry.key === entryKey)) {
        return;
    }

    state.candidateHistory.unshift({
        key: entryKey,
        move: snapshot.lastMove,
        moveNumber: Math.ceil(latestMove.ply / 2),
        lines: snapshot.candidateLines
    });
    state.candidateHistory = state.candidateHistory.slice(0, 24);
    state.scrollCandidateHistoryToTop = true;
}

function animateLastMove() {
    if (!state.snapshot || !state.previousSnapshot) {
        return;
    }
    if (!state.snapshot.lastMove || state.snapshot.lastMove === state.previousSnapshot.lastMove) {
        return;
    }
    const from = state.snapshot.lastMove.slice(0, 2);
    const to = state.snapshot.lastMove.slice(2, 4);
    const previousPieces = fenToPieceMap(state.previousSnapshot.fen);
    const piece = previousPieces.get(from);
    if (!piece) {
        return;
    }

    const fromElement = elements.board.querySelector(`[data-square="${from}"]`);
    const toElement = elements.board.querySelector(`[data-square="${to}"]`);
    if (!fromElement || !toElement) {
        return;
    }

    const boardRect = elements.board.getBoundingClientRect();
    const fromRect = fromElement.getBoundingClientRect();
    const toRect = toElement.getBoundingClientRect();
    const animatedPiece = document.createElement("span");
    animatedPiece.className = `piece animated-piece ${piece.color}`;
    animatedPiece.textContent = piece.glyph;
    animatedPiece.style.left = `${fromRect.left - boardRect.left}px`;
    animatedPiece.style.top = `${fromRect.top - boardRect.top}px`;
    elements.boardAnimationLayer.textContent = "";
    elements.boardAnimationLayer.appendChild(animatedPiece);

    const deltaX = toRect.left - fromRect.left;
    const deltaY = toRect.top - fromRect.top;
    window.requestAnimationFrame(() => {
        animatedPiece.style.transform = `translate(${deltaX}px, ${deltaY}px)`;
    });
    window.setTimeout(() => {
        if (animatedPiece.parentElement === elements.boardAnimationLayer) {
            animatedPiece.remove();
        }
    }, 260);
}

function legalMovesByFrom() {
    const map = new Map();
    if (!state.snapshot?.legalMoves) {
        return map;
    }
    for (const move of state.snapshot.legalMoves) {
        const list = map.get(move.from) || [];
        list.push(move);
        map.set(move.from, list);
    }
    return map;
}

function fenToPieceMap(fen) {
    const [boardPart] = fen.split(" ");
    const ranks = boardPart.split("/");
    const pieces = new Map();
    for (let fenRank = 0; fenRank < 8; fenRank++) {
        let file = 0;
        for (const char of ranks[fenRank]) {
            if (/\d/.test(char)) {
                file += Number(char);
                continue;
            }
            const rank = 7 - fenRank;
            const square = `${String.fromCharCode(97 + file)}${rank + 1}`;
            pieces.set(square, PIECES[char]);
            file++;
        }
    }
    return pieces;
}

function placeholder(text) {
    const element = document.createElement("div");
    element.className = "placeholder";
    element.textContent = text;
    return element;
}

function appendHighlightedMoveText(container, text) {
    let lastIndex = 0;
    for (const match of text.matchAll(UCI_MOVE_PATTERN)) {
        const [move] = match;
        const index = match.index ?? 0;
        if (index > lastIndex) {
            container.appendChild(document.createTextNode(text.slice(lastIndex, index)));
        }
        const badge = document.createElement("span");
        badge.className = "move-highlight";
        badge.textContent = move;
        container.appendChild(badge);
        lastIndex = index + move.length;
    }
    if (lastIndex < text.length) {
        container.appendChild(document.createTextNode(text.slice(lastIndex)));
    }
}

function syncSidebarHeight() {
    if (!state.snapshot || window.innerWidth <= 1060) {
        elements.sidebar.style.removeProperty("height");
        return;
    }

    const boardCardHeight = Math.ceil(elements.boardCard.getBoundingClientRect().height);
    if (boardCardHeight > 0) {
        elements.sidebar.style.height = `${boardCardHeight}px`;
    }
}

function setStatus(text, busy = false) {
    elements.statusPill.textContent = text;
    elements.statusPill.classList.toggle("busy", busy);
}

function persistSession(sessionId) {
    state.sessionId = sessionId;
    window.sessionStorage.setItem("agentic-chess-session", sessionId);
}

function clearSession() {
    clearTimeout(state.reconnectTimer);
    if (state.socket) {
        const socket = state.socket;
        state.socket = null;
        socket.close();
    }
    state.sessionId = null;
    state.snapshot = null;
    state.previousSnapshot = null;
    state.selectedSquare = null;
    state.hoverSquare = null;
    state.candidateHistory = [];
    state.commentaryPhase = "";
    state.commentaryStream = "";
    state.scrollCandidateHistoryToTop = false;
    state.reconnecting = false;
    state.requestInFlight = false;
    state.uiMode = null;
    window.sessionStorage.removeItem("agentic-chess-session");
}
