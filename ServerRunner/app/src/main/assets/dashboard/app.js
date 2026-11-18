const loginPanel = document.getElementById("login");
const dashboardPanel = document.getElementById("dashboard");
const authMessage = document.getElementById("auth-message");
const loginError = document.getElementById("login-error");
const stateError = document.getElementById("state-error");
const codeInput = document.getElementById("code");
const logoutButton = document.getElementById("logout");
const requestCodeButton = document.getElementById("request-code");
const serverCards = new Map();
const errorLogLine = /\b(error|err|fatal|exception|traceback|failed|refused|denied)\b|[A-Za-z]+Error\b/i;
const successLogLine = /application startup complete|uvicorn running on|server is running|listening on|ready to accept|registered tunnel connection|quick tunnel has been created/i;
let refreshTimer = null;
let resendTimer = null;

async function request(path, options = {}) {
  const response = await fetch(path, {
    cache: "no-store",
    credentials: "same-origin",
    ...options,
  });
  let data = {};
  try {
    data = await response.json();
  } catch {
    data = {};
  }
  if (!response.ok) {
    const error = new Error(data.error || `Request failed (${response.status})`);
    error.status = response.status;
    error.retryAfter = data.retryAfter || 0;
    throw error;
  }
  return data;
}

function showLogin(message) {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
  dashboardPanel.classList.add("hidden");
  logoutButton.classList.add("hidden");
  loginPanel.classList.remove("hidden");
  authMessage.textContent = message;
  loginError.textContent = "";
}

function showDashboard() {
  loginPanel.classList.add("hidden");
  dashboardPanel.classList.remove("hidden");
  logoutButton.classList.remove("hidden");
  requestCodeButton.classList.add("hidden");
  if (resendTimer) {
    clearInterval(resendTimer);
    resendTimer = null;
  }
  if (!refreshTimer) {
    refreshState();
    refreshTimer = setInterval(refreshState, 1500);
  }
}

function showCodeRequest(retryAfter) {
  requestCodeButton.classList.remove("hidden");
  if (resendTimer) {
    clearInterval(resendTimer);
    resendTimer = null;
  }
  let seconds = Math.max(0, Number(retryAfter) || 0);
  const update = () => {
    requestCodeButton.disabled = seconds > 0;
    requestCodeButton.textContent = seconds > 0
      ? `ส่งรหัสอีกครั้ง (${seconds} วินาที)`
      : "ส่งรหัสอีกครั้ง";
    if (seconds > 0) seconds -= 1;
    else if (resendTimer) {
      clearInterval(resendTimer);
      resendTimer = null;
    }
  };
  update();
  if (seconds > 0) resendTimer = setInterval(update, 1000);
}

async function checkAuth() {
  try {
    const result = await request("/api/auth");
    if (result.authenticated) {
      showDashboard();
      return;
    }
    let message = "รหัส 6 หลักถูกส่งไปยัง Notification บนมือถือ และใช้ได้ครั้งเดียวภายใน 5 นาที";
    if (result.challenge === "locked") message = `พักการขอรหัสชั่วคราว (${result.retryAfter} วินาที)`;
    else if (result.challenge === "wait") message = `รอสักครู่แล้วลองใหม่ (${result.retryAfter} วินาที)`;
    else if (result.challenge === "unavailable") message = "ส่ง Notification ไม่สำเร็จ กรุณาตรวจการอนุญาต Notification";
    authMessage.textContent = message;
    loginPanel.classList.remove("hidden");
    showCodeRequest(result.challenge === "unavailable" ? 0 : result.retryAfter);
  } catch (error) {
    showLogin(error.message);
  }
}

function setBadge(element, value) {
  element.textContent = value || "ไม่ทราบสถานะ";
  element.className = "badge";
  if ((value || "").includes("ปกติ")) element.classList.add("good");
  else if ((value || "").includes("Error")) element.classList.add("bad");
}

function updateLog(element, value) {
  const next = value || "ยังไม่มี Logs";
  if (element.textContent === next) return;
  const follow = element.scrollHeight - element.scrollTop - element.clientHeight < 24;
  const lines = next.split("\n");
  element.replaceChildren();
  lines.forEach((line, index) => {
    const span = document.createElement("span");
    span.className = "log-line";
    if (errorLogLine.test(line)) span.classList.add("log-error");
    else if (successLogLine.test(line)) span.classList.add("log-success");
    span.textContent = line;
    element.append(span);
    if (index < lines.length - 1) element.append(document.createTextNode("\n"));
  });
  if (follow) element.scrollTop = element.scrollHeight;
}

function createServerCard() {
  const root = document.createElement("article");
  root.className = "server-card";
  const heading = document.createElement("div");
  heading.className = "server-heading";
  const name = document.createElement("h3");
  name.className = "server-name";
  const badges = document.createElement("div");
  badges.className = "badges";
  const serverStatus = document.createElement("span");
  const tunnelStatus = document.createElement("span");
  badges.append(serverStatus, tunnelStatus);
  heading.append(name, badges);

  const meta = document.createElement("p");
  meta.className = "meta";
  const domain = document.createElement("a");
  domain.className = "domain hidden";
  domain.target = "_blank";
  domain.rel = "noopener noreferrer";

  const serverTitle = document.createElement("h4");
  serverTitle.className = "log-title";
  serverTitle.textContent = "Server Logs";
  const serverLog = document.createElement("pre");
  serverLog.className = "log";
  const tunnelTitle = document.createElement("h4");
  tunnelTitle.className = "log-title";
  tunnelTitle.textContent = "Tunnel Logs";
  const tunnelLog = document.createElement("pre");
  tunnelLog.className = "log";

  root.append(heading, meta, domain, serverTitle, serverLog, tunnelTitle, tunnelLog);
  return { root, name, serverStatus, tunnelStatus, meta, domain, serverLog, tunnelLog };
}

function updateServerCard(server) {
  let card = serverCards.get(server.id);
  if (!card) {
    card = createServerCard();
    serverCards.set(server.id, card);
    document.getElementById("servers").append(card.root);
  }
  card.name.textContent = server.name;
  setBadge(card.serverStatus, `Server: ${server.serverStatus}`);
  setBadge(card.tunnelStatus, `Tunnel: ${server.tunnelStatus}`);
  const databases = server.databases.length ? server.databases.join(", ") : "ยังไม่มี";
  card.meta.textContent = `Port ${server.port} · ไฟล์: ${server.file} · SQLite: ${databases}`;
  if (server.domain) {
    card.domain.textContent = server.domain;
    card.domain.href = server.domain;
    card.domain.classList.remove("hidden");
  } else {
    card.domain.textContent = "";
    card.domain.removeAttribute("href");
    card.domain.classList.add("hidden");
  }
  updateLog(card.serverLog, server.serverLog);
  updateLog(card.tunnelLog, server.tunnelLog);
}

async function refreshState() {
  try {
    const state = await request("/api/state");
    stateError.textContent = "";
    const dashboardUrl = document.getElementById("dashboard-url");
    if (state.dashboardUrl) {
      dashboardUrl.textContent = state.dashboardUrl;
      dashboardUrl.href = state.dashboardUrl;
      dashboardUrl.classList.remove("hidden");
    } else {
      dashboardUrl.textContent = "";
      dashboardUrl.removeAttribute("href");
      dashboardUrl.classList.add("hidden");
    }
    setBadge(document.getElementById("dashboard-server-status"), `Dashboard: ${state.dashboardServerStatus}`);
    setBadge(document.getElementById("dashboard-tunnel-status"), `Tunnel: ${state.dashboardTunnelStatus}`);
    updateLog(document.getElementById("dashboard-tunnel-log"), state.dashboardTunnelLog);
    updateLog(document.getElementById("dashboard-server-log"), state.dashboardServerLog);
    const present = new Set();
    for (const server of state.servers) {
      present.add(server.id);
      updateServerCard(server);
    }
    for (const [id, card] of serverCards) {
      if (!present.has(id)) {
        card.root.remove();
        serverCards.delete(id);
      }
    }
    document.getElementById("updated").textContent =
      `อัปเดต ${new Date().toLocaleTimeString()}`;
  } catch (error) {
    if (error.status === 401) {
      showLogin("Session หมดอายุ กรุณาขอรหัสใหม่จาก Notification");
      checkAuth();
    } else {
      stateError.textContent = error.message;
    }
  }
}

document.getElementById("code-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  loginError.textContent = "";
  try {
    await request("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: codeInput.value.trim() }),
    });
    codeInput.value = "";
    showDashboard();
  } catch (error) {
    loginError.textContent = error.status === 429
      ? `กรอกรหัสผิดหลายครั้ง กรุณารอ ${error.retryAfter} วินาที`
      : error.message;
  }
});

requestCodeButton.addEventListener("click", checkAuth);

logoutButton.addEventListener("click", async () => {
  try {
    await request("/api/logout", { method: "POST" });
  } catch {
    // The session may already have expired.
  }
  showLogin("ออกจากระบบแล้ว");
  checkAuth();
});

checkAuth();
