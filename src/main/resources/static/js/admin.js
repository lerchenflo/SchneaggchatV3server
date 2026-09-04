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
 * Fetch wrapper for every /admin/api/** call. A 401 means the access token expired - there is no
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

const TABS = ['changelog', 'donations', 'connected', 'logs'];

function switchTab(tab) {
    TABS.forEach((t) => {
        document.getElementById(`admin-tab-${t}`).classList.toggle('active', t === tab);
        document.getElementById(`admin-section-${t}`).classList.toggle('hidden', t !== tab);
    });

    if (tab === 'changelog' && !changelogState.loadedOnce) loadChangeLog(true);
    if (tab === 'donations' && !donationsState.loadedOnce) loadDonations();
    if (tab === 'connected' && !connectedUsersState.streamStarted) startConnectedUsersStream();
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

/* ---------------------------------------------------------------------- */
/* Map change log                                                         */
/* ---------------------------------------------------------------------- */

const changelogState = { page: 0, editedBy: '', loadedOnce: false, moreEntries: false };

async function loadChangeLogEditors() {
    const editors = await adminFetchJson('/admin/api/map/changelog/editors');
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

    const pageData = await adminFetchJson(`/admin/api/map/changelog?${params}`);
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
    const donations = await adminFetchJson('/admin/api/donations');
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
        const path = donationsState.editingId ? `/admin/api/donations/${donationsState.editingId}` : '/admin/api/donations';
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
    await adminFetchJson(`/admin/api/donations/${id}`, { method: 'DELETE' });
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
        renderConnectedUsers(await adminFetchJson('/admin/api/connected-users'));
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
        const response = await fetch('/admin/api/connected-users/stream', {
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
/* Error / event logs                                                     */
/* ---------------------------------------------------------------------- */

const logsState = { page: 0, logType: 'EXCEPTION_THROWN', loadedOnce: false };

async function loadLogTypes() {
    const types = await adminFetchJson('/admin/api/logs/types');
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

    const params = new URLSearchParams({ logType: logsState.logType, page: logsState.page, pageSize: '50' });
    const pageData = await adminFetchJson(`/admin/api/logs?${params}`);
    logsState.loadedOnce = true;

    const tbody = document.getElementById('logs-rows');
    pageData.entries.forEach((entry) => {
        const row = el('tr');
        row.appendChild(el('td', null, formatDateTime(entry.timestamp)));
        row.appendChild(el('td', null, entry.logType));
        row.appendChild(el('td', null, entry.username || '—'));
        row.appendChild(el('td', 'admin-log-message', entry.message || ''));
        tbody.appendChild(row);
    });

    document.getElementById('logs-load-more').classList.toggle('hidden', !pageData.moreEntries);
}

document.getElementById('logs-type-filter')?.addEventListener('change', (e) => {
    logsState.logType = e.target.value;
    loadLogs(true);
});

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
        showPanelView();
        switchTab('changelog');
    } catch (e) {
        if (e instanceof SessionExpiredError) {
            // adminFetch already cleared tokens and switched to the login view - nothing more to do.
            return;
        }
        // A logged-in-but-not-admin user gets 404s from every /admin/api/** call (see AdminGuard);
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
