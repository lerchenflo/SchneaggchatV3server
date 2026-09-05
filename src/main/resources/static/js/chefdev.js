// Admin panel: login, authenticated fetch wrapper, and the four tabs (map change log, donations,
// connected users, error logs). German only - this page is seen by two people, so it carries no
// data-i18n keys and is not part of the strings-*.xml translation system.

/* ---------------------------------------------------------------------- */
/* Auth                                                                    */
/* ---------------------------------------------------------------------- */

// The access token lives in this variable and nowhere else - no sessionStorage, no localStorage, and
// the refresh token from the login response is deliberately discarded. Closing the tab ends the
// session, and once the access token expires the admin logs in again. Nothing an XSS on this origin
// could steal outlives the page.
let accessToken = null;

async function login(username, password) {
    const response = await fetch('/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password, deviceName: 'Adminpanel', deviceType: 'WEB' }),
    });

    if (!response.ok) {
        if (response.status === 429) {
            throw new Error('Zu viele fehlgeschlagene Anmeldeversuche. Bitte später erneut versuchen.');
        }
        throw new Error('Falscher Benutzername oder falsches Passwort.');
    }

    const data = await response.json();
    accessToken = data.accessToken;
}

class SessionExpiredError extends Error {}

function logout() {
    accessToken = null;
    stopConnectedUsersStream();
    resetTabState();
    showLoginView();
}

// Sessions are short now, so a tab must reload its data on the next login instead of showing what
// the previous session left behind.
function resetTabState() {
    changelogState.loadedOnce = false;
    changelogState.page = 0;
    donationsState.loadedOnce = false;
    logsState.loadedOnce = false;
    logsState.page = 0;
}

/**
 * Fetch wrapper for every /chefdev/api/** call. A 401 means the access token expired - there is no
 * refresh token to fall back on, so the panel returns to the login screen.
 */
async function adminFetch(path, options = {}) {
    const response = await fetch(path, {
        ...options,
        headers: {
            ...(options.headers || {}),
            Authorization: `Bearer ${accessToken}`,
        },
    });

    if (response.status === 401) {
        logout();
        throw new SessionExpiredError('Sitzung abgelaufen.');
    }

    return response;
}

async function adminFetchJson(path, options = {}) {
    const response = await adminFetch(path, options);
    if (response.status === 404) {
        throw new NotAdminError();
    }
    if (!response.ok) {
        const text = await response.text().catch(() => '');
        throw new Error(text || `Anfrage fehlgeschlagen (${response.status})`);
    }
    if (response.status === 204) return null;
    return response.json();
}

class NotAdminError extends Error {}

// A tab load started before the token expired rejects after logout() already put the login screen
// back up - there is nothing left for the call site to do about it.
window.addEventListener('unhandledrejection', (event) => {
    if (event.reason instanceof SessionExpiredError) event.preventDefault();
});

/* ---------------------------------------------------------------------- */
/* View switching                                                         */
/* ---------------------------------------------------------------------- */

function showLoginView() {
    document.getElementById('admin-login-view').classList.remove('hidden');
    document.getElementById('admin-panel-view').classList.add('hidden');
    document.getElementById('admin-denied-view').classList.add('hidden');
}

function showDeniedView() {
    document.getElementById('admin-login-view').classList.add('hidden');
    document.getElementById('admin-panel-view').classList.add('hidden');
    document.getElementById('admin-denied-view').classList.remove('hidden');
}

function showPanelView() {
    document.getElementById('admin-login-view').classList.add('hidden');
    document.getElementById('admin-panel-view').classList.remove('hidden');
    document.getElementById('admin-denied-view').classList.add('hidden');
}

// Order matches the top bar: read-only views first, data-changing views after the separator.
const TABS = ['connected', 'changelog', 'tree', 'logs', 'donations', 'scores', 'users'];

function switchTab(tab) {
    TABS.forEach((t) => {
        document.getElementById(`admin-tab-${t}`).classList.toggle('active', t === tab);
        document.getElementById(`admin-section-${t}`).classList.toggle('hidden', t !== tab);
    });

    // The tab row scrolls horizontally, so the selected tab may be off screen - pull it into view
    // without scrolling the page itself.
    document.getElementById(`admin-tab-${tab}`)
        ?.scrollIntoView({ behavior: 'smooth', inline: 'nearest', block: 'nearest' });

    if (tab === 'changelog' && !changelogState.loadedOnce) loadChangeLog(true);
    if (tab === 'donations' && !donationsState.loadedOnce) loadDonations();
    if (tab === 'connected' && !connectedUsersState.streamStarted) startConnectedUsersStream();
    if (tab === 'scores' && !scoresState.loadedOnce) loadScores(true);
    if (tab === 'users' && !usersState.loadedOnce) loadUsers();
    if (tab === 'tree' && !treeState.loadedOnce) loadFriendsTree();
    if (tab === 'logs' && !logsState.loadedOnce) loadLogs(true);
}

/* ---------------------------------------------------------------------- */
/* Formatting helpers                                                     */
/* ---------------------------------------------------------------------- */

function formatDateTime(epochMillis) {
    return new Date(epochMillis).toLocaleString('de-DE', {
        day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
    });
}

function formatEuros(cents) {
    return `€${(cents / 100).toFixed(2)}`;
}

function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

/**
 * Wires a table header row for click-to-sort. `state` carries `sortKey`/`sortAsc`; clicking the
 * active column flips the direction, clicking another switches to it using `defaultDirections`
 * (so e.g. dates start newest-first while names start A-Z). `onChange` re-runs the query/render.
 */
function attachSortableHeader(headerId, state, defaultDirections, onChange) {
    const header = document.getElementById(headerId);
    if (!header) return;

    header.addEventListener('click', (e) => {
        const cell = e.target.closest('.admin-sortable');
        if (!cell) return;

        const key = cell.dataset.sort;
        if (state.sortKey === key) {
            state.sortAsc = !state.sortAsc;
        } else {
            state.sortKey = key;
            state.sortAsc = defaultDirections[key] === 'asc';
        }
        renderSortIndicators(headerId, state);
        onChange();
    });

    renderSortIndicators(headerId, state);
}

function renderSortIndicators(headerId, state) {
    document.querySelectorAll(`#${headerId} .admin-sortable`).forEach((cell) => {
        const active = cell.dataset.sort === state.sortKey;
        cell.classList.toggle('is-sorted', active);
        cell.classList.toggle('is-desc', active && !state.sortAsc);
    });
}

/* ---------------------------------------------------------------------- */
/* Map change log                                                         */
/* ---------------------------------------------------------------------- */

const changelogState = { page: 0, editedBy: '', loadedOnce: false, moreEntries: false };

async function loadChangeLogEditors() {
    const editors = await adminFetchJson('/chefdev/api/map/changelog/editors');
    const select = document.getElementById('changelog-editor-filter');
    select.innerHTML = '<option value="">Alle Nutzer</option>';
    editors
        .sort((a, b) => a.username.localeCompare(b.username))
        .forEach((editor) => {
            const option = el('option', null, editor.username);
            option.value = editor.userId;
            select.appendChild(option);
        });
}

async function loadChangeLog(reset) {
    if (reset) {
        changelogState.page = 0;
        document.getElementById('changelog-rows').innerHTML = '';
    }

    const params = new URLSearchParams({ page: changelogState.page, pageSize: '30' });
    if (changelogState.editedBy) params.set('editedBy', changelogState.editedBy);

    const pageData = await adminFetchJson(`/chefdev/api/map/changelog?${params}`);
    changelogState.loadedOnce = true;
    changelogState.moreEntries = pageData.moreEntries;

    const tbody = document.getElementById('changelog-rows');
    pageData.entries.forEach((entry) => {
        const row = el('tr');
        row.appendChild(el('td', null, formatDateTime(entry.editedAt)));
        row.appendChild(el('td', null, entry.editedByUsername));
        row.appendChild(el('td', null, entry.entryName || `(gelöscht: ${entry.entryId})`));

        const typeCell = el('td');
        typeCell.appendChild(el('span', `admin-badge admin-badge-${entry.changeType.toLowerCase()}`, entry.changeType));
        row.appendChild(typeCell);

        const changesCell = el('td', 'admin-changes-cell');
        if (entry.changes.length === 0) {
            changesCell.appendChild(el('span', 'admin-muted', '—'));
        } else {
            entry.changes.forEach((change) => {
                const line = el('div');
                line.appendChild(el('strong', null, `${change.field}: `));
                line.appendChild(el('span', null, `${change.oldValue ?? '–'} → ${change.newValue ?? '–'}`));
                changesCell.appendChild(line);
            });
        }
        row.appendChild(changesCell);

        tbody.appendChild(row);
    });

    document.getElementById('changelog-load-more').classList.toggle('hidden', !pageData.moreEntries);
}

document.getElementById('changelog-editor-filter')?.addEventListener('change', (e) => {
    changelogState.editedBy = e.target.value;
    loadChangeLog(true);
});

document.getElementById('changelog-load-more')?.addEventListener('click', () => {
    changelogState.page += 1;
    loadChangeLog(false);
});

/* ---------------------------------------------------------------------- */
/* Donations                                                              */
/* ---------------------------------------------------------------------- */

const donationsState = { loadedOnce: false, editingId: null };

async function loadDonations() {
    const donations = await adminFetchJson('/chefdev/api/donations');
    donationsState.loadedOnce = true;

    const tbody = document.getElementById('donations-rows');
    tbody.innerHTML = '';

    donations.forEach((donation) => {
        const row = el('tr');
        if (donation.deleted) row.classList.add('admin-row-deleted');

        row.appendChild(el('td', null, formatDateTime(donation.donatedAt).split(',')[0]));
        row.appendChild(el('td', null, donation.name));
        row.appendChild(el('td', null, formatEuros(donation.amountCents)));
        row.appendChild(el('td', null, donation.message || ''));
        row.appendChild(el('td', null, donation.deleted ? 'Gelöscht' : 'Aktiv'));

        const actionsCell = el('td', 'admin-actions-cell');
        if (!donation.deleted) {
            const editButton = el('button', 'secondary-button admin-inline-button', 'Bearbeiten');
            editButton.addEventListener('click', () => openDonationForm(donation));
            actionsCell.appendChild(editButton);

            const deleteButton = el('button', 'delete-button admin-inline-button', 'Löschen');
            deleteButton.addEventListener('click', () => deleteDonation(donation.id));
            actionsCell.appendChild(deleteButton);
        }
        row.appendChild(actionsCell);

        tbody.appendChild(row);
    });
}

function openDonationForm(donation) {
    donationsState.editingId = donation ? donation.id : null;
    document.getElementById('donation-form-title').textContent = donation ? 'Spende bearbeiten' : 'Neue Spende';
    document.getElementById('donation-form-name').value = donation ? donation.name : '';
    document.getElementById('donation-form-amount').value = donation ? (donation.amountCents / 100).toFixed(2) : '';
    document.getElementById('donation-form-date').value = donation
        ? new Date(donation.donatedAt).toISOString().slice(0, 10)
        : new Date().toISOString().slice(0, 10);
    document.getElementById('donation-form-message').value = donation ? (donation.message || '') : '';
    document.getElementById('donation-form-error').textContent = '';
    document.getElementById('donation-form-modal').classList.remove('hidden');
}

function closeDonationForm() {
    document.getElementById('donation-form-modal').classList.add('hidden');
}

document.getElementById('donation-add-button')?.addEventListener('click', () => openDonationForm(null));
document.getElementById('donation-form-cancel')?.addEventListener('click', closeDonationForm);

document.getElementById('donation-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const errorEl = document.getElementById('donation-form-error');
    errorEl.textContent = '';

    const name = document.getElementById('donation-form-name').value.trim();
    const amountEuros = parseFloat(document.getElementById('donation-form-amount').value);
    const dateValue = document.getElementById('donation-form-date').value;
    const message = document.getElementById('donation-form-message').value.trim();

    if (!name || Number.isNaN(amountEuros) || amountEuros <= 0 || !dateValue) {
        errorEl.textContent = 'Bitte Name, Betrag und Datum korrekt ausfüllen.';
        return;
    }

    const body = JSON.stringify({
        name,
        amountCents: Math.round(amountEuros * 100),
        donatedAt: new Date(dateValue).getTime(),
        message: message || null,
    });

    try {
        const path = donationsState.editingId ? `/chefdev/api/donations/${donationsState.editingId}` : '/chefdev/api/donations';
        await adminFetchJson(path, {
            method: donationsState.editingId ? 'PUT' : 'POST',
            headers: { 'Content-Type': 'application/json' },
            body,
        });
        closeDonationForm();
        loadDonations();
    } catch (err) {
        errorEl.textContent = err.message || 'Speichern fehlgeschlagen.';
    }
});

async function deleteDonation(id) {
    if (!confirm('Diese Spende wirklich löschen?')) return;
    await adminFetchJson(`/chefdev/api/donations/${id}`, { method: 'DELETE' });
    loadDonations();
}

/* ---------------------------------------------------------------------- */
/* Connected users (SSE via fetch, since EventSource can't send a header) */
/* ---------------------------------------------------------------------- */

const connectedUsersState = { streamStarted: false, abortController: null };

function renderConnectedUsers(snapshot) {
    const tbody = document.getElementById('connected-users-rows');
    tbody.innerHTML = '';

    document.getElementById('connected-users-count').textContent = String(snapshot.users.length);

    snapshot.users.forEach((user) => {
        const row = el('tr');
        row.appendChild(el('td', null, user.username));
        row.appendChild(el('td', null, String(user.sessionCount)));
        row.appendChild(el('td', null, formatDateTime(user.onlineSince)));
        tbody.appendChild(row);
    });
}

function stopConnectedUsersStream() {
    connectedUsersState.abortController?.abort();
    connectedUsersState.abortController = null;
    connectedUsersState.streamStarted = false;
}

async function startConnectedUsersStream() {
    connectedUsersState.streamStarted = true;

    // Initial snapshot so the table isn't empty while the stream connects. Admin access was
    // already confirmed to reach this screen, so any failure here is transient - the SSE
    // connection below will fill the table in shortly regardless.
    try {
        renderConnectedUsers(await adminFetchJson('/chefdev/api/connected-users'));
    } catch (e) {
        // ignore - stream below will populate it
    }

    connectStream();
}

async function connectStream() {
    if (!accessToken) return;

    const abortController = new AbortController();
    connectedUsersState.abortController = abortController;

    try {
        const response = await fetch('/chefdev/api/connected-users/stream', {
            headers: { Authorization: `Bearer ${accessToken}` },
            signal: abortController.signal,
        });

        if (response.status === 401) {
            logout();
            return;
        }
        if (!response.ok || !response.body) throw new Error('stream failed');

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });

            let boundary;
            while ((boundary = buffer.indexOf('\n\n')) !== -1) {
                const rawEvent = buffer.slice(0, boundary);
                buffer = buffer.slice(boundary + 2);

                const dataLine = rawEvent.split('\n').find((line) => line.startsWith('data:'));
                if (dataLine) {
                    try {
                        renderConnectedUsers(JSON.parse(dataLine.slice(5).trim()));
                    } catch (e) { /* ignore malformed frame */ }
                }
            }
        }
    } catch (e) {
        // fallthrough to reconnect below
    }

    // Stream ended - server restart, network blip, or the server closing it because the access token
    // that opened it expired. Reconnect after a short delay as long as we're still logged in; if the
    // token is dead the reconnect gets a 401 and drops back to the login screen.
    if (accessToken && connectedUsersState.abortController === abortController) {
        setTimeout(connectStream, 3000);
    }
}

/* ---------------------------------------------------------------------- */
/* Highscores                                                             */
/* ---------------------------------------------------------------------- */

const scoresState = {
    page: 0, game: '', difficulty: '', loadedOnce: false, editingId: null,
    sortKey: 'DATE', sortAsc: false,
};

const SCORE_SORT_DEFAULT_DIR = { GAME: 'asc', USER: 'asc', SCORE: 'desc', DATE: 'desc' };

function formatDuration(millis) {
    if (!millis) return '—';
    const totalSeconds = Math.floor(millis / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return minutes > 0 ? `${minutes}:${String(seconds).padStart(2, '0')} min` : `${(millis / 1000).toFixed(2)} s`;
}

async function loadScoreFilters() {
    const data = await adminFetchJson('/chefdev/api/scores/games');

    const gameSelect = document.getElementById('scores-game-filter');
    gameSelect.innerHTML = '<option value="">Alle Spiele</option>';
    data.games.forEach((game) => {
        const option = el('option', null, game);
        option.value = game;
        gameSelect.appendChild(option);
    });

    const difficultySelect = document.getElementById('scores-difficulty-filter');
    difficultySelect.innerHTML = '<option value="">Alle Schwierigkeiten</option>';
    data.difficulties.forEach((difficulty) => {
        const option = el('option', null, difficulty);
        option.value = difficulty;
        difficultySelect.appendChild(option);
    });
}

async function loadScores(reset) {
    if (reset) {
        scoresState.page = 0;
        document.getElementById('scores-rows').innerHTML = '';
    }

    const params = new URLSearchParams({
        sort: scoresState.sortKey,
        ascending: String(scoresState.sortAsc),
        page: scoresState.page,
        pageSize: '50',
    });
    if (scoresState.game) params.set('game', scoresState.game);
    if (scoresState.difficulty) params.set('difficulty', scoresState.difficulty);

    const pageData = await adminFetchJson(`/chefdev/api/scores?${params}`);
    scoresState.loadedOnce = true;

    const tbody = document.getElementById('scores-rows');
    pageData.entries.forEach((entry) => {
        const row = el('tr');
        row.appendChild(el('td', null, entry.game));
        row.appendChild(el('td', null, entry.difficulty));
        row.appendChild(el('td', null, entry.username));
        row.appendChild(el('td', null, String(entry.score)));
        row.appendChild(el('td', null, formatDuration(entry.timeMillis)));
        row.appendChild(el('td', null, formatDateTime(entry.createdAt)));

        const actionsCell = el('td', 'admin-actions-cell');
        const editButton = el('button', 'secondary-button admin-inline-button', 'Bearbeiten');
        editButton.addEventListener('click', () => openScoreForm(entry));
        actionsCell.appendChild(editButton);

        const deleteButton = el('button', 'delete-button admin-inline-button', 'Löschen');
        deleteButton.addEventListener('click', () => deleteScore(entry));
        actionsCell.appendChild(deleteButton);
        row.appendChild(actionsCell);

        tbody.appendChild(row);
    });

    document.getElementById('scores-load-more').classList.toggle('hidden', !pageData.moreEntries);
}

function openScoreForm(entry) {
    scoresState.editingId = entry.id;
    document.getElementById('score-form-context').textContent =
        `${entry.username} · ${entry.game} · ${entry.difficulty}`;
    document.getElementById('score-form-score').value = entry.score;
    document.getElementById('score-form-time').value = entry.timeMillis;
    document.getElementById('score-form-error').textContent = '';
    document.getElementById('score-form-modal').classList.remove('hidden');
}

function closeScoreForm() {
    document.getElementById('score-form-modal').classList.add('hidden');
}

document.getElementById('score-form-cancel')?.addEventListener('click', closeScoreForm);

document.getElementById('score-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const errorEl = document.getElementById('score-form-error');
    errorEl.textContent = '';

    const score = parseInt(document.getElementById('score-form-score').value, 10);
    const timeMillis = parseInt(document.getElementById('score-form-time').value, 10);

    if (Number.isNaN(score) || score < 0 || Number.isNaN(timeMillis) || timeMillis < 0) {
        errorEl.textContent = 'Punkte und Zeit müssen Zahlen ≥ 0 sein.';
        return;
    }

    try {
        await adminFetchJson(`/chefdev/api/scores/${scoresState.editingId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ score, timeMillis }),
        });
        closeScoreForm();
        loadScores(true);
    } catch (err) {
        errorEl.textContent = err.message || 'Speichern fehlgeschlagen.';
    }
});

async function deleteScore(entry) {
    if (!confirm(`Highscore von ${entry.username} (${entry.game}, ${entry.score} Punkte) wirklich löschen?`)) return;
    await adminFetchJson(`/chefdev/api/scores/${entry.id}`, { method: 'DELETE' });
    loadScores(true);
}

document.getElementById('scores-game-filter')?.addEventListener('change', (e) => {
    scoresState.game = e.target.value;
    loadScores(true);
});

document.getElementById('scores-difficulty-filter')?.addEventListener('change', (e) => {
    scoresState.difficulty = e.target.value;
    loadScores(true);
});

attachSortableHeader('scores-header', scoresState, SCORE_SORT_DEFAULT_DIR, () => loadScores(true));

document.getElementById('scores-load-more')?.addEventListener('click', () => {
    scoresState.page += 1;
    loadScores(false);
});

/* ---------------------------------------------------------------------- */
/* Users                                                                  */
/* ---------------------------------------------------------------------- */

const usersState = { loadedOnce: false, all: [], search: '', sortKey: 'createdAt', sortAsc: true };

// Which direction a column starts in when you first click it: names A-Z, everything else
// "most interesting first" (newest, most devices, online before offline).
const USER_SORT_DEFAULT_DIR = {
    username: 'asc',
    createdAt: 'asc',
    lastSeen: 'desc',
    activeDevices: 'desc',
    online: 'desc',
};

async function loadUsers() {
    usersState.all = await adminFetchJson('/chefdev/api/users');
    usersState.loadedOnce = true;
    renderUsers();
}

function sortUsers(users) {
    const { sortKey, sortAsc } = usersState;
    const factor = sortAsc ? 1 : -1;

    return [...users].sort((a, b) => {
        let result;
        if (sortKey === 'username') {
            result = a.username.localeCompare(b.username);
        } else if (sortKey === 'online') {
            // Online first, then the longest-online (earliest lastSeen is meaningless while
            // online, so fall back to device count) - keeps the grouping readable.
            result = (a.online === b.online) ? (a.activeDevices - b.activeDevices) : (a.online ? 1 : -1);
        } else {
            result = a[sortKey] - b[sortKey];
        }
        return result * factor || a.username.localeCompare(b.username);
    });
}

function renderUsers() {
    const tbody = document.getElementById('users-rows');
    tbody.innerHTML = '';

    renderSortIndicators('users-header', usersState);

    const term = usersState.search.trim().toLowerCase();
    const filtered = term ? usersState.all.filter((u) => u.username.toLowerCase().includes(term)) : usersState.all;
    const visible = sortUsers(filtered);

    visible.forEach((user) => {
        const row = el('tr');

        const nameCell = el('td', 'admin-name-cell');
        nameCell.appendChild(el('span', null, user.username));
        if (user.role === 'ADMIN') {
            nameCell.appendChild(el('span', 'admin-badge admin-badge-update', 'Admin'));
        }
        row.appendChild(nameCell);

        row.appendChild(el('td', null, formatDateTime(user.createdAt)));
        row.appendChild(el('td', null, user.online ? '—' : formatDateTime(user.lastSeen)));
        row.appendChild(el('td', null, String(user.activeDevices)));

        const statusCell = el('td');
        statusCell.appendChild(
            el('span', `admin-badge ${user.online ? 'admin-badge-create' : 'admin-badge-offline'}`,
                user.online ? 'Online' : 'Offline')
        );
        row.appendChild(statusCell);

        const actionsCell = el('td', 'admin-actions-cell');
        const logoutButton = el('button', 'delete-button admin-inline-button', 'Überall abmelden');
        logoutButton.disabled = user.activeDevices === 0 && !user.online;
        logoutButton.addEventListener('click', () => forceLogout(user));
        actionsCell.appendChild(logoutButton);
        row.appendChild(actionsCell);

        tbody.appendChild(row);
    });
}

async function forceLogout(user) {
    if (!confirm(`${user.username} auf allen Geräten abmelden?\n\nAlle Sitzungen werden gelöscht und offene Verbindungen getrennt.`)) return;
    await adminFetchJson(`/chefdev/api/users/${user.id}/logout`, { method: 'POST' });
    loadUsers();
}

document.getElementById('users-search')?.addEventListener('input', (e) => {
    usersState.search = e.target.value;
    renderUsers();
});

attachSortableHeader('users-header', usersState, USER_SORT_DEFAULT_DIR, renderUsers);

/* ---------------------------------------------------------------------- */
/* Friends tree                                                           */
/* ---------------------------------------------------------------------- */

const treeState = { loadedOnce: false, zoom: 1 };

const TREE_MIN_ZOOM = 0.2;
const TREE_MAX_ZOOM = 3;

function buildTreeNode(node, isRoot) {
    const li = el('li');

    const card = el('div', 'admin-tree-node');
    if (isRoot) {
        // A root is someone with no earlier-registered first friend - i.e. nobody invited them.
        // Split them apart: one that has descendants actually started a tree, one without is
        // simply an isolated account.
        card.classList.add(node.children.length > 0 ? 'admin-tree-node-root' : 'admin-tree-node-lone');
    }

    card.appendChild(el('span', 'admin-tree-name', node.username));

    const meta = el('div', 'admin-tree-meta');
    meta.appendChild(el('span', null, `seit ${new Date(node.createdAt).toLocaleDateString('de-DE')}`));
    meta.appendChild(el('span', null, `${node.friendCount} Freunde`));
    card.appendChild(meta);

    if (isRoot) {
        card.appendChild(
            el('span', 'admin-tree-root-badge', node.children.length > 0 ? 'Baum-Start' : 'Ohne Einlader')
        );
    }

    li.appendChild(card);

    if (node.children.length > 0) {
        const ul = el('ul');
        node.children.forEach((child) => ul.appendChild(buildTreeNode(child, false)));
        li.appendChild(ul);
    }

    return li;
}

async function loadFriendsTree() {
    const tree = await adminFetchJson('/chefdev/api/friends-tree');
    treeState.loadedOnce = true;

    const treeStarters = tree.roots.filter((root) => root.children.length > 0).length;
    const loneUsers = tree.roots.length - treeStarters;
    document.getElementById('tree-summary').textContent =
        `${tree.totalUsers} Nutzer · ${treeStarters} Baum-Start(s) · ${loneUsers} ohne Einlader`;

    const container = document.getElementById('tree-container');
    container.innerHTML = '';

    const rootList = el('ul');
    tree.roots.forEach((root) => rootList.appendChild(buildTreeNode(root, true)));
    container.appendChild(rootList);

    applyTreeZoom(treeState.zoom);
}

/* --- Pan / zoom -------------------------------------------------------- */

/**
 * The tree is scaled with a CSS transform (which doesn't affect layout), so the canvas around it
 * is resized to the scaled dimensions - that's what gives the viewport correct native scrollbars
 * on desktop and correct one-finger scrolling on mobile.
 *
 * When a focal point is given (cursor position, pinch centre) the scroll offset is corrected so
 * that point stays put instead of the view jumping to the top-left corner.
 */
function applyTreeZoom(zoom, focalClientX, focalClientY) {
    const viewport = document.getElementById('tree-viewport');
    const canvas = document.getElementById('tree-canvas');
    const container = document.getElementById('tree-container');
    if (!viewport || !canvas || !container) return;

    const clamped = Math.min(TREE_MAX_ZOOM, Math.max(TREE_MIN_ZOOM, zoom));
    const previous = treeState.zoom;
    const rect = viewport.getBoundingClientRect();

    const focalX = focalClientX === undefined ? rect.width / 2 : focalClientX - rect.left;
    const focalY = focalClientY === undefined ? rect.height / 2 : focalClientY - rect.top;

    const anchorX = viewport.scrollLeft + focalX;
    const anchorY = viewport.scrollTop + focalY;

    treeState.zoom = clamped;
    container.style.transform = `scale(${clamped})`;
    canvas.style.width = `${container.offsetWidth * clamped}px`;
    canvas.style.height = `${container.offsetHeight * clamped}px`;

    const ratio = clamped / previous;
    viewport.scrollLeft = anchorX * ratio - focalX;
    viewport.scrollTop = anchorY * ratio - focalY;

    document.getElementById('tree-zoom-label').textContent = `${Math.round(clamped * 100)}%`;
}

document.getElementById('tree-zoom-in')?.addEventListener('click', () => applyTreeZoom(treeState.zoom * 1.25));
document.getElementById('tree-zoom-out')?.addEventListener('click', () => applyTreeZoom(treeState.zoom / 1.25));
document.getElementById('tree-zoom-reset')?.addEventListener('click', () => applyTreeZoom(1));

// Ctrl/⌘ + wheel, and trackpad pinch (which browsers report as a ctrlKey wheel event).
// A plain wheel is left alone so it still scrolls the viewport natively.
document.getElementById('tree-viewport')?.addEventListener('wheel', (e) => {
    if (!e.ctrlKey && !e.metaKey) return;
    e.preventDefault();
    applyTreeZoom(treeState.zoom * (e.deltaY < 0 ? 1.12 : 1 / 1.12), e.clientX, e.clientY);
}, { passive: false });

// Drag to pan with a mouse. Touch is left to the browser's native scrolling.
(() => {
    const viewport = document.getElementById('tree-viewport');
    if (!viewport) return;

    let panning = false;
    let startX = 0;
    let startY = 0;
    let startScrollLeft = 0;
    let startScrollTop = 0;

    viewport.addEventListener('pointerdown', (e) => {
        if (e.pointerType !== 'mouse' || e.button !== 0) return;
        panning = true;
        startX = e.clientX;
        startY = e.clientY;
        startScrollLeft = viewport.scrollLeft;
        startScrollTop = viewport.scrollTop;
        viewport.classList.add('is-panning');
        viewport.setPointerCapture(e.pointerId);
    });

    viewport.addEventListener('pointermove', (e) => {
        if (!panning) return;
        viewport.scrollLeft = startScrollLeft - (e.clientX - startX);
        viewport.scrollTop = startScrollTop - (e.clientY - startY);
    });

    const endPan = (e) => {
        if (!panning) return;
        panning = false;
        viewport.classList.remove('is-panning');
        if (viewport.hasPointerCapture(e.pointerId)) viewport.releasePointerCapture(e.pointerId);
    };

    viewport.addEventListener('pointerup', endPan);
    viewport.addEventListener('pointercancel', endPan);
})();

// Two-finger pinch zoom on touch devices. One finger keeps scrolling natively (touch-action in CSS).
(() => {
    const viewport = document.getElementById('tree-viewport');
    if (!viewport) return;

    let pinchStartDistance = 0;
    let pinchStartZoom = 1;

    const distance = (touches) => Math.hypot(
        touches[0].clientX - touches[1].clientX,
        touches[0].clientY - touches[1].clientY,
    );

    viewport.addEventListener('touchstart', (e) => {
        if (e.touches.length !== 2) return;
        pinchStartDistance = distance(e.touches);
        pinchStartZoom = treeState.zoom;
    }, { passive: true });

    viewport.addEventListener('touchmove', (e) => {
        if (e.touches.length !== 2 || pinchStartDistance === 0) return;
        e.preventDefault();
        const centerX = (e.touches[0].clientX + e.touches[1].clientX) / 2;
        const centerY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
        applyTreeZoom(pinchStartZoom * (distance(e.touches) / pinchStartDistance), centerX, centerY);
    }, { passive: false });

    viewport.addEventListener('touchend', (e) => {
        if (e.touches.length < 2) pinchStartDistance = 0;
    }, { passive: true });
})();

/* ---------------------------------------------------------------------- */
/* Error / event logs                                                     */
/* ---------------------------------------------------------------------- */

const logsState = { page: 0, logType: 'EXCEPTION_THROWN', loadedOnce: false, sortKey: 'DATE', sortAsc: false };

const LOG_SORT_DEFAULT_DIR = { DATE: 'desc', TYPE: 'asc', USER: 'asc' };

async function loadLogTypes() {
    const types = await adminFetchJson('/chefdev/api/logs/types');
    const select = document.getElementById('logs-type-filter');
    select.innerHTML = '<option value="ALL">Alle Typen</option>';
    types.forEach((type) => {
        const option = el('option', null, type);
        option.value = type;
        if (type === logsState.logType) option.selected = true;
        select.appendChild(option);
    });
}

async function loadLogs(reset) {
    if (reset) {
        logsState.page = 0;
        document.getElementById('logs-rows').innerHTML = '';
    }

    const params = new URLSearchParams({
        logType: logsState.logType,
        sort: logsState.sortKey,
        ascending: String(logsState.sortAsc),
        page: logsState.page,
        pageSize: '50',
    });
    const pageData = await adminFetchJson(`/chefdev/api/logs?${params}`);
    logsState.loadedOnce = true;

    const tbody = document.getElementById('logs-rows');
    pageData.entries.forEach((entry) => {
        const row = el('tr');
        row.appendChild(el('td', null, formatDateTime(entry.timestamp)));
        row.appendChild(el('td', null, entry.logType));

        // The user id is what the "Nutzer" column actually sorts on, so surface it next to the
        // resolved name - otherwise a sorted-by-user list looks arbitrarily ordered.
        const userCell = el('td', 'admin-user-cell');
        userCell.appendChild(el('span', null, entry.username || '—'));
        if (entry.userId) userCell.appendChild(el('span', 'admin-user-id', entry.userId));
        row.appendChild(userCell);

        row.appendChild(el('td', 'admin-log-message', entry.message || ''));
        tbody.appendChild(row);
    });

    document.getElementById('logs-load-more').classList.toggle('hidden', !pageData.moreEntries);
}

document.getElementById('logs-type-filter')?.addEventListener('change', (e) => {
    logsState.logType = e.target.value;
    loadLogs(true);
});

attachSortableHeader('logs-header', logsState, LOG_SORT_DEFAULT_DIR, () => loadLogs(true));

document.getElementById('logs-load-more')?.addEventListener('click', () => {
    logsState.page += 1;
    loadLogs(false);
});

/* ---------------------------------------------------------------------- */
/* Bootstrap                                                              */
/* ---------------------------------------------------------------------- */

document.getElementById('admin-login-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const errorEl = document.getElementById('admin-login-error');
    errorEl.textContent = '';

    const username = document.getElementById('admin-login-username').value.trim();
    const password = document.getElementById('admin-login-password').value;

    try {
        await login(username, password);
        await enterPanel();
    } catch (err) {
        errorEl.textContent = err.message || 'Login fehlgeschlagen.';
    }
});

document.getElementById('admin-logout-button')?.addEventListener('click', logout);
document.getElementById('admin-denied-logout-button')?.addEventListener('click', logout);

TABS.forEach((tab) => {
    document.getElementById(`admin-tab-${tab}`)?.addEventListener('click', () => switchTab(tab));
});

async function enterPanel() {
    try {
        await loadChangeLogEditors();
        await loadLogTypes();
        await loadScoreFilters();
        showPanelView();
        switchTab(TABS[0]);
    } catch (e) {
        if (e instanceof SessionExpiredError) {
            // adminFetch already cleared tokens and switched to the login view - nothing more to do.
            return;
        }
        // A logged-in-but-not-admin user gets 404s from every /chefdev/api/** call (see AdminGuard);
        // any other failure here also means the panel can't be shown, so the same screen covers both.
        showDeniedView();
    }
}

// No session survives a page load - nothing is persisted, so every visit starts at the login form.
function init() {
    showLoginView();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
