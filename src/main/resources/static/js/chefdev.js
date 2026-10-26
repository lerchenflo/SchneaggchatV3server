// Admin panel: login, authenticated fetch wrapper, the monitoring dashboard and the data tabs (map
// change log, donations, connected users, error logs, ...). German only - this page is seen by two people, so it carries no
// data-i18n keys and is not part of the strings-*.xml translation system.

/* ---------------------------------------------------------------------- */
/* Auth                                                                    */
/* ---------------------------------------------------------------------- */

// The access token lives in this variable and nowhere else - no sessionStorage, no localStorage, and
// the server issues no refresh token for this WEB login at all. Closing the tab ends the
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
    faqState.loadedOnce = false;
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
const TABS = ['monitoring', 'connected', 'changelog', 'tree', 'logs', 'donations', 'faq', 'scores', 'users', 'server'];

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
    if (tab === 'faq' && !faqState.loadedOnce) loadFaqEntries();
    if (tab === 'connected' && !connectedUsersState.streamStarted) startConnectedUsersStream();
    if (tab === 'scores' && !scoresState.loadedOnce) loadScores(true);
    if (tab === 'users' && !usersState.loadedOnce) loadUsers();
    if (tab === 'tree' && !treeState.loadedOnce) loadFriendsTree();
    if (tab === 'logs' && !logsState.loadedOnce) loadLogs(true);
    // Reloaded on every visit - no live updates, the tab switch is the refresh.
    if (tab === 'monitoring') loadMonitoring();
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
    const sorted = [...editors].sort((a, b) => a.username.localeCompare(b.username));

    const filterSelect = document.getElementById('changelog-editor-filter');
    filterSelect.innerHTML = '<option value="">Alle Nutzer</option>';
    sorted.forEach((editor) => {
        const option = el('option', null, editor.username);
        option.value = editor.userId;
        filterSelect.appendChild(option);
    });

    // Same list powers the "revert this user's changes" tool below the table.
    const revertSelect = document.getElementById('revert-user-select');
    if (revertSelect) {
        revertSelect.innerHTML = '<option value="">Nutzer wählen…</option>';
        sorted.forEach((editor) => {
            const option = el('option', null, editor.username);
            option.value = editor.userId;
            revertSelect.appendChild(option);
        });
    }
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
        if (entry.revertOfVersionId) {
            changesCell.appendChild(el('div', 'admin-muted', '↩ macht eine Änderung rückgängig'));
        } else if (entry.revertNote) {
            changesCell.appendChild(el('div', 'admin-muted', `↩ ${entry.revertNote}`));
        }
        row.appendChild(changesCell);

        const actionsCell = el('td', 'admin-actions-cell');
        const undoButton = el('button', 'secondary-button admin-inline-button', 'Rückgängig');
        undoButton.addEventListener('click', () => revertChange(entry));
        actionsCell.appendChild(undoButton);
        row.appendChild(actionsCell);

        tbody.appendChild(row);
    });

    document.getElementById('changelog-load-more').classList.toggle('hidden', !pageData.moreEntries);
}

/** Undoes exactly this one change, regardless of what happened to the entry afterwards. */
async function revertChange(entry) {
    const target = entry.entryName || `Eintrag ${entry.entryId}`;
    if (!confirm(`Diese Änderung an "${target}" wirklich rückgängig machen?`)) return;

    try {
        await adminFetchJson(`/chefdev/api/map/changelog/${entry.id}/revert`, { method: 'POST' });
        loadChangeLog(true);
    } catch (err) {
        alert(err.message || 'Rückgängig machen fehlgeschlagen.');
    }
}

/* ---------------------------------------------------------------------- */
/* Map change log - bulk revert tools                                     */
/* ---------------------------------------------------------------------- */

document.getElementById('revert-user-button')?.addEventListener('click', async () => {
    const errorEl = document.getElementById('revert-user-error');
    errorEl.textContent = '';

    const userId = document.getElementById('revert-user-select').value;
    const fromValue = document.getElementById('revert-user-from').value;
    const toValue = document.getElementById('revert-user-to').value;
    const username = document.getElementById('revert-user-select').selectedOptions[0]?.textContent || '';

    if (!userId || !fromValue || !toValue) {
        errorEl.textContent = 'Bitte Nutzer sowie Von- und Bis-Zeitpunkt wählen.';
        return;
    }

    const fromMillis = new Date(fromValue).getTime();
    const toMillis = new Date(toValue).getTime();
    if (fromMillis > toMillis) {
        errorEl.textContent = 'Der Von-Zeitpunkt muss vor dem Bis-Zeitpunkt liegen.';
        return;
    }

    if (!confirm(
        `Alle Kartenänderungen von "${username}" zwischen ${formatDateTime(fromMillis)} und ` +
        `${formatDateTime(toMillis)} rückgängig machen? Betroffene Einträge werden auf ihren Stand ` +
        `direkt vor der ersten Änderung von "${username}" in diesem Zeitraum zurückgesetzt.`
    )) return;

    try {
        const result = await adminFetchJson('/chefdev/api/map/revert-user', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId, fromMillis, toMillis }),
        });
        alert(`${result.revertedCount} Eintrag/Einträge zurückgesetzt.`);
        loadChangeLog(true);
    } catch (err) {
        errorEl.textContent = err.message || 'Rückgängig machen fehlgeschlagen.';
    }
});

document.getElementById('restore-timestamp-button')?.addEventListener('click', async () => {
    const errorEl = document.getElementById('restore-timestamp-error');
    errorEl.textContent = '';

    const value = document.getElementById('restore-timestamp-input').value;
    if (!value) {
        errorEl.textContent = 'Bitte einen Zeitpunkt wählen.';
        return;
    }

    const timestampMillis = new Date(value).getTime();
    if (timestampMillis > Date.now()) {
        errorEl.textContent = 'Der Zeitpunkt darf nicht in der Zukunft liegen.';
        return;
    }

    if (!confirm(
        `Die GESAMTE Karte auf den Stand von ${formatDateTime(timestampMillis)} zurücksetzen? ` +
        `Jede seitdem von irgendjemandem gemachte Änderung geht dabei verloren. Das kann nicht ` +
        `rückgängig gemacht werden (außer erneut über die Änderungshistorie).`
    )) return;

    try {
        const result = await adminFetchJson('/chefdev/api/map/restore-to-timestamp', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ timestampMillis }),
        });
        alert(`${result.revertedCount} Eintrag/Einträge zurückgesetzt.`);
        loadChangeLog(true);
    } catch (err) {
        errorEl.textContent = err.message || 'Zurücksetzen fehlgeschlagen.';
    }
});

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
/* FAQ                                                                    */
/* ---------------------------------------------------------------------- */

// German labels for the category enum served by /chefdev/api/faq/categories. A category the server
// knows but this map does not still shows up, under its raw enum name.
const FAQ_CATEGORY_LABELS = {
    GENERAL: 'Allgemein',
    ACCOUNT: 'Account',
    CHATS: 'Chats',
    NOTIFICATIONS: 'Benachrichtigungen',
    MAP: 'Karte',
    PRIVACY: 'Datenschutz',
    TECHNICAL: 'Technisches',
};

const faqState = { loadedOnce: false, editingId: null, categoriesLoaded: false };

function faqCategoryLabel(category) {
    return FAQ_CATEGORY_LABELS[category] || category;
}

async function loadFaqCategories() {
    if (faqState.categoriesLoaded) return;

    const categories = await adminFetchJson('/chefdev/api/faq/categories');
    const select = document.getElementById('faq-form-category');
    select.innerHTML = '';
    categories.forEach((category) => {
        const option = el('option', null, faqCategoryLabel(category));
        option.value = category;
        select.appendChild(option);
    });
    faqState.categoriesLoaded = true;
}

function translationBadge(label, present) {
    return el('span', `admin-badge ${present ? 'admin-badge-create' : 'admin-badge-offline'}`, label);
}

async function loadFaqEntries() {
    const [entries] = await Promise.all([
        adminFetchJson('/chefdev/api/faq'),
        loadFaqCategories(),
    ]);
    faqState.loadedOnce = true;

    const tbody = document.getElementById('faq-rows');
    tbody.innerHTML = '';

    entries.forEach((entry) => {
        const row = el('tr');
        if (entry.deleted) row.classList.add('admin-row-deleted');

        row.appendChild(el('td', null, faqCategoryLabel(entry.category)));
        row.appendChild(el('td', null, String(entry.sortOrder)));
        row.appendChild(el('td', null, entry.german.question));

        const translationsCell = el('td', 'admin-actions-cell');
        translationsCell.appendChild(translationBadge('AT', Boolean(entry.austrian)));
        translationsCell.appendChild(translationBadge('EN', Boolean(entry.english)));
        row.appendChild(translationsCell);

        row.appendChild(el('td', null, entry.deleted ? 'Gelöscht' : 'Aktiv'));

        const actionsCell = el('td', 'admin-actions-cell');
        if (!entry.deleted) {
            const editButton = el('button', 'secondary-button admin-inline-button', 'Bearbeiten');
            editButton.addEventListener('click', () => openFaqForm(entry));
            actionsCell.appendChild(editButton);

            const deleteButton = el('button', 'delete-button admin-inline-button', 'Löschen');
            deleteButton.addEventListener('click', () => deleteFaqEntry(entry.id));
            actionsCell.appendChild(deleteButton);
        }
        row.appendChild(actionsCell);

        tbody.appendChild(row);
    });
}

function openFaqForm(entry) {
    faqState.editingId = entry ? entry.id : null;
    document.getElementById('faq-form-title').textContent = entry ? 'FAQ-Eintrag bearbeiten' : 'Neuer FAQ-Eintrag';
    document.getElementById('faq-form-category').value = entry ? entry.category : 'GENERAL';
    document.getElementById('faq-form-sort').value = entry ? entry.sortOrder : 0;
    document.getElementById('faq-form-question-de').value = entry ? entry.german.question : '';
    document.getElementById('faq-form-answer-de').value = entry ? entry.german.answer : '';
    document.getElementById('faq-form-question-at').value = entry?.austrian ? entry.austrian.question : '';
    document.getElementById('faq-form-answer-at').value = entry?.austrian ? entry.austrian.answer : '';
    document.getElementById('faq-form-question-en').value = entry?.english ? entry.english.question : '';
    document.getElementById('faq-form-answer-en').value = entry?.english ? entry.english.answer : '';
    document.getElementById('faq-form-error').textContent = '';
    document.getElementById('faq-form-modal').classList.remove('hidden');
}

function closeFaqForm() {
    document.getElementById('faq-form-modal').classList.add('hidden');
}

async function deleteFaqEntry(id) {
    if (!confirm('Diesen FAQ-Eintrag wirklich löschen?')) return;
    await adminFetchJson(`/chefdev/api/faq/${id}`, { method: 'DELETE' });
    loadFaqEntries();
}

document.getElementById('faq-add-button')?.addEventListener('click', () => openFaqForm(null));
document.getElementById('faq-form-cancel')?.addEventListener('click', closeFaqForm);

// A translation counts as filled in only when both its question and its answer are there - half a
// translation would show a German answer under an English question on the website.
function readFaqTranslation(suffix, errorEl, languageName) {
    const question = document.getElementById(`faq-form-question-${suffix}`).value.trim();
    const answer = document.getElementById(`faq-form-answer-${suffix}`).value.trim();

    if (!question && !answer) return null;
    if (!question || !answer) {
        errorEl.textContent = `${languageName}: bitte Frage und Antwort ausfüllen oder beide leer lassen.`;
        throw new Error(errorEl.textContent);
    }
    return { question, answer };
}

document.getElementById('faq-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const errorEl = document.getElementById('faq-form-error');
    errorEl.textContent = '';

    const question = document.getElementById('faq-form-question-de').value.trim();
    const answer = document.getElementById('faq-form-answer-de').value.trim();
    const sortOrder = parseInt(document.getElementById('faq-form-sort').value, 10);

    if (!question || !answer || Number.isNaN(sortOrder) || sortOrder < 0) {
        errorEl.textContent = 'Bitte deutsche Frage, Antwort und eine gültige Position ausfüllen.';
        return;
    }

    let austrian;
    let english;
    try {
        austrian = readFaqTranslation('at', errorEl, 'Vorarlbergerisch');
        english = readFaqTranslation('en', errorEl, 'Englisch');
    } catch {
        return; // message already in errorEl
    }

    const body = JSON.stringify({
        category: document.getElementById('faq-form-category').value,
        sortOrder,
        german: { question, answer },
        austrian,
        english,
    });

    try {
        const path = faqState.editingId ? `/chefdev/api/faq/${faqState.editingId}` : '/chefdev/api/faq';
        await adminFetchJson(path, {
            method: faqState.editingId ? 'PUT' : 'POST',
            headers: { 'Content-Type': 'application/json' },
            body,
        });
        closeFaqForm();
        loadFaqEntries();
    } catch (err) {
        errorEl.textContent = err.message || 'Speichern fehlgeschlagen.';
    }
});

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

const DAY_MS = 24 * 60 * 60 * 1000;

// Divider buckets for the "Zuletzt gesehen" sort, widest window last. `days` counts whole days
// back from the start of today, so the groups line up with calendar days instead of the clock
// time the page happened to be opened at.
const LAST_SEEN_BUCKETS = [
    { label: 'Heute', days: 0 },
    { label: 'Letzte 3 Tage', days: 2 },
    { label: 'Letzte Woche', days: 6 },
    { label: 'Letzte 2 Wochen', days: 13 },
    { label: 'Letzter Monat', days: 29 },
];

function lastSeenBucketLabel(lastSeen, startOfToday) {
    const bucket = LAST_SEEN_BUCKETS.find((b) => lastSeen >= startOfToday - b.days * DAY_MS);
    return bucket ? bucket.label : 'Älter';
}

function lastSeenDividerRow(label) {
    const row = el('tr', 'admin-divider-row');
    const cell = el('td', null, label);
    cell.colSpan = document.querySelectorAll('#users-header th').length;
    row.appendChild(cell);
    return row;
}

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

    const groupByLastSeen = usersState.sortKey === 'lastSeen';
    const startOfToday = new Date().setHours(0, 0, 0, 0);
    let currentBucket = null;

    visible.forEach((user) => {
        if (groupByLastSeen) {
            const bucket = lastSeenBucketLabel(user.lastSeen, startOfToday);
            if (bucket !== currentBucket) {
                currentBucket = bucket;
                tbody.appendChild(lastSeenDividerRow(bucket));
            }
        }

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

const TREE_MIN_ZOOM = 0.02;
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

document.getElementById('tree-zoom-in')?.addEventListener('click', () => applyTreeZoom(treeState.zoom * 1.4));
document.getElementById('tree-zoom-out')?.addEventListener('click', () => applyTreeZoom(treeState.zoom / 1.4));
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
/* Monitoring dashboard                                                   */
/* ---------------------------------------------------------------------- */

const monitoringState = {
    range: 'HOURS_24',
    overview: null,
    // Status code the "endpoints with the most errors" table is filtered to, null = all
    endpointStatus: null,
    errorTypes: [],
    errorsAxis: null,
    errorsSearch: '',
    sortKey: 'count',
    sortAsc: false,
    logsPage: 0,
};

const ERROR_SORT_DEFAULT_DIR = { tag: 'asc', level: 'asc', count: 'desc', affectedUsers: 'desc', lastSeen: 'desc' };

const endpointUsageState = {
    endpoints: [],
    search: '',
    sortKey: 'count',
    sortAsc: false,
};

const USAGE_SORT_DEFAULT_DIR = { endpoint: 'asc', count: 'desc', failed: 'desc', lastHour: 'desc' };

const MON_COLORS = {
    error: '#ef4444',
    warn: '#f59e0b',
    info: '#2e77cc',
    ok: '#1ed860',
    purple: '#a855f7',
    gray: '#64748b',
};

const SVG_NS = 'http://www.w3.org/2000/svg';

function formatNumber(value, digits = 0) {
    if (value == null || Number.isNaN(value)) return '—';
    return Number(value).toLocaleString('de-DE', { maximumFractionDigits: digits, minimumFractionDigits: 0 });
}

function formatUptime(seconds) {
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    if (days > 0) return `${days} T ${hours} h`;
    if (hours > 0) return `${hours} h ${minutes} min`;
    return `${minutes} min`;
}

function formatAxisTime(millis, bucketMs) {
    const date = new Date(millis);
    const time = date.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
    if (bucketMs < 60 * 60 * 1000) return time;
    return `${date.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' })} ${time}`;
}

function svgEl(tag, attrs) {
    const node = document.createElementNS(SVG_NS, tag);
    Object.entries(attrs).forEach(([key, value]) => node.setAttribute(key, value));
    return node;
}

/** Rounds a chart's maximum up to 1/2/5 x 10^n so the grid lines land on readable values. */
function niceMax(value) {
    if (value <= 0) return 1;
    const magnitude = 10 ** Math.floor(Math.log10(value));
    const step = [1, 2, 5, 10].find((m) => m * magnitude >= value);
    return step * magnitude;
}

/**
 * Draws a column chart into `container` with plain SVG. `series` = [{ name, color, values }] on
 * the shared `axis` ({ start, bucketMs, buckets }); values may be null for "no data". Stacked by
 * default; `mini` drops axes and labels for the per-error sparklines. Hovering shows a tooltip
 * with every series' value for that column.
 */
function renderColumnChart(container, axis, series, opts = {}) {
    container.innerHTML = '';

    const mini = !!opts.mini;
    // Never wider than the box - a fixed minimum width overflowed narrow grid columns. A hidden
    // container (width 0) gets a fallback and is redrawn once it is shown, see chartResizeObserver.
    const width = mini ? Math.max(container.clientWidth || 0, 120) : (container.clientWidth || 300);
    container._chart = { axis, series, opts, width };
    if (!mini) chartResizeObserver.observe(container);
    const height = opts.height || (mini ? 36 : 220);
    const pad = mini ? { l: 0, r: 0, t: 2, b: 0 } : { l: 48, r: 8, t: 10, b: 22 };
    const stacked = opts.stacked !== false;
    const format = opts.format || ((v) => formatNumber(v));
    const n = axis.buckets;

    const columnValue = (i) => stacked
        ? series.reduce((sum, s) => sum + (s.values[i] || 0), 0)
        : Math.max(0, ...series.map((s) => s.values[i] || 0));
    const max = niceMax(Math.max(0, ...Array.from({ length: n }, (_, i) => columnValue(i))));

    const plotW = width - pad.l - pad.r;
    const plotH = height - pad.t - pad.b;
    const colW = plotW / n;
    const gap = colW > 4 ? Math.min(2, colW * 0.2) : 0;

    const svg = svgEl('svg', { width, height, class: 'mon-chart-svg' });

    if (!mini) {
        for (let g = 0; g <= 4; g++) {
            const y = pad.t + plotH - (plotH * g) / 4;
            svg.appendChild(svgEl('line', { x1: pad.l, x2: width - pad.r, y1: y, y2: y, class: 'mon-grid-line' }));
            const label = svgEl('text', { x: pad.l - 6, y: y + 4, class: 'mon-axis-label', 'text-anchor': 'end' });
            label.textContent = format((max * g) / 4);
            svg.appendChild(label);
        }
        const ticks = Math.min(6, n);
        for (let t = 0; t < ticks; t++) {
            const i = Math.round((t * (n - 1)) / Math.max(1, ticks - 1));
            const label = svgEl('text', {
                x: pad.l + i * colW + colW / 2,
                y: height - 6,
                class: 'mon-axis-label',
                'text-anchor': t === 0 ? 'start' : t === ticks - 1 ? 'end' : 'middle',
            });
            label.textContent = formatAxisTime(axis.start + i * axis.bucketMs, axis.bucketMs);
            svg.appendChild(label);
        }
    }

    for (let i = 0; i < n; i++) {
        const x = pad.l + i * colW + gap / 2;
        let base = pad.t + plotH;
        series.forEach((s, si) => {
            const v = s.values[i];
            if (!v) return;
            const h = Math.max(1, (v / max) * plotH);
            if (stacked) {
                base -= h;
                svg.appendChild(svgEl('rect', { x, y: base, width: Math.max(1, colW - gap), height: h, fill: s.color }));
            } else {
                const w = Math.max(1, (colW - gap) / series.length);
                svg.appendChild(svgEl('rect', {
                    x: x + si * w, y: pad.t + plotH - h, width: w, height: h, fill: s.color,
                }));
            }
        });
    }

    const tooltip = document.getElementById('mon-tooltip');
    svg.addEventListener('mousemove', (e) => {
        const rect = svg.getBoundingClientRect();
        const i = Math.floor((e.clientX - rect.left - pad.l) / colW);
        if (i < 0 || i >= n) {
            tooltip.classList.add('hidden');
            return;
        }
        tooltip.innerHTML = '';
        tooltip.appendChild(el('div', 'mon-tooltip-time', formatDateTime(axis.start + i * axis.bucketMs)));
        series.forEach((s) => {
            const line = el('div', 'mon-tooltip-line');
            const swatch = el('span', 'mon-swatch');
            swatch.style.background = s.color;
            line.appendChild(swatch);
            line.appendChild(document.createTextNode(`${s.name}: ${s.values[i] == null ? '—' : format(s.values[i])}`));
            tooltip.appendChild(line);
        });
        tooltip.style.left = `${Math.min(e.clientX + 14, window.innerWidth - 220)}px`;
        tooltip.style.top = `${e.clientY + 14}px`;
        tooltip.classList.remove('hidden');
    });
    svg.addEventListener('mouseleave', () => tooltip.classList.add('hidden'));

    container.appendChild(svg);

    if (!mini && series.length > 1) {
        const legend = el('div', 'mon-legend');
        series.forEach((s) => {
            const item = el('span', 'mon-legend-item');
            const swatch = el('span', 'mon-swatch');
            swatch.style.background = s.color;
            item.appendChild(swatch);
            item.appendChild(document.createTextNode(s.name));
            legend.appendChild(item);
        });
        container.appendChild(legend);
    }
}

// Charts are drawn at their container's pixel width - redraw one whenever that width changes
// (window resize, grid/row switch, the monitoring section shown after loading while hidden).
// Redrawn in the next frame, so the redraw itself never triggers a resize loop warning.
const chartResizeObserver = new ResizeObserver((entries) => {
    requestAnimationFrame(() => {
        entries.forEach(({ target }) => {
            const chart = target._chart;
            if (chart && target.isConnected && target.clientWidth > 0 && target.clientWidth !== chart.width) {
                renderColumnChart(target, chart.axis, chart.series, chart.opts);
            }
        });
    });
});

function kpiTile(label, value, previous, invert = true) {
    const tile = el('div', 'mon-kpi');
    tile.appendChild(el('div', 'mon-kpi-label', label));
    tile.appendChild(el('div', 'mon-kpi-value', typeof value === 'number' ? formatNumber(value) : value));
    if (previous != null && typeof value === 'number') {
        const diff = value - previous;
        // For errors, going up is bad - so up is red, down is green.
        const trend = el('div', `mon-kpi-trend ${diff === 0 ? '' : (diff > 0) === invert ? 'is-bad' : 'is-good'}`);
        trend.textContent = diff === 0
            ? '± 0 zum Zeitraum davor'
            : `${diff > 0 ? '▲' : '▼'} ${formatNumber(Math.abs(diff))} zum Zeitraum davor`;
        tile.appendChild(trend);
    }
    return tile;
}

function renderOverview(data) {
    const kpis = document.getElementById('mon-kpis');
    kpis.innerHTML = '';
    kpis.appendChild(kpiTile('Fehler', data.current.errors, data.previous.errors));
    kpis.appendChild(kpiTile('Warnungen', data.current.warnings, data.previous.warnings));
    kpis.appendChild(kpiTile('Fehlgeschl. Requests', data.current.failedRequests, data.previous.failedRequests));
    kpis.appendChild(kpiTile('Serverfehler (5xx)', data.current.serverErrors, data.previous.serverErrors));
    kpis.appendChild(kpiTile('Betroffene Nutzer', data.current.affectedUsers, data.previous.affectedUsers));

    const status = document.getElementById('mon-status');
    status.innerHTML = '';
    const m = data.latestMetrics;
    if (m) {
        const devices = Object.values(m.activeDevices || {}).reduce((a, b) => a + b, 0);
        status.appendChild(kpiTile('Uptime', formatUptime(m.uptimeSeconds)));
        status.appendChild(kpiTile('Heap', `${formatNumber(m.heapUsedMb)} / ${formatNumber(m.heapMaxMb)} MB`));
        status.appendChild(kpiTile('CPU (Prozess)', m.processCpuPercent < 0 ? '—' : `${formatNumber(m.processCpuPercent, 1)} %`));
        status.appendChild(kpiTile('Socket-Verbindungen', `${formatNumber(m.socketConnections)} (${formatNumber(m.socketUsers)} Nutzer)`));
        status.appendChild(kpiTile('Aktive Geräte', devices));
        status.appendChild(kpiTile('Mongo / Redis', `${m.mongoPingMs < 0 ? '✕' : formatNumber(m.mongoPingMs, 1)} / ${m.redisPingMs < 0 ? '✕' : formatNumber(m.redisPingMs, 1)} ms`));
    } else {
        status.appendChild(el('p', 'admin-muted', 'Noch keine Server-Messwerte (erster Messpunkt eine Minute nach dem Start).'));
    }

    renderColumnChart(document.getElementById('mon-chart-levels'), data.axis, [
        { name: 'Fehler', color: MON_COLORS.error, values: data.errorSeries },
        { name: 'Warnungen', color: MON_COLORS.warn, values: data.warningSeries },
    ]);
    renderColumnChart(document.getElementById('mon-chart-requests'), data.axis, [
        { name: '5xx', color: MON_COLORS.error, values: data.serverErrorSeries },
        { name: '4xx', color: MON_COLORS.warn, values: data.clientErrorSeries },
    ]);

    monitoringState.overview = data;
    // A status filter survives a refresh as long as that status still has failures
    if (!data.statusCodes.some((code) => code.status === monitoringState.endpointStatus)) {
        monitoringState.endpointStatus = null;
    }
    renderStatusCodes();
    renderTopEndpoints();
}

/** Clicking a status code narrows the endpoint table to that status; clicking it again (or "Alle anzeigen") undoes it. */
function setEndpointStatusFilter(status) {
    monitoringState.endpointStatus = status;
    renderStatusCodes();
    renderTopEndpoints();
}

function renderStatusCodes() {
    const data = monitoringState.overview;
    const selected = monitoringState.endpointStatus;
    const codes = document.getElementById('mon-status-codes');
    codes.innerHTML = '';
    const maxCount = Math.max(1, ...data.statusCodes.map((c) => c.count));
    if (data.statusCodes.length === 0) codes.appendChild(el('p', 'admin-muted', 'Keine fehlgeschlagenen Requests.'));
    data.statusCodes.forEach((code) => {
        const active = code.status === selected;
        const row = el('div', `mon-bar-row mon-clickable${active ? ' is-active' : selected != null ? ' is-dimmed' : ''}`);
        row.title = active ? 'Filter aufheben' : `Nur Endpunkte mit ${code.status} anzeigen`;
        row.appendChild(el('span', 'mon-bar-label', String(code.status)));
        const track = el('div', 'mon-bar-track');
        const bar = el('div', 'mon-bar');
        bar.style.width = `${(code.count / maxCount) * 100}%`;
        bar.style.background = code.status >= 500 ? MON_COLORS.error : code.status === 429 ? MON_COLORS.purple : MON_COLORS.warn;
        track.appendChild(bar);
        row.appendChild(track);
        row.appendChild(el('span', 'mon-bar-value', formatNumber(code.count)));
        row.addEventListener('click', () => setEndpointStatusFilter(active ? null : code.status));
        codes.appendChild(row);
    });
}

function renderTopEndpoints() {
    const data = monitoringState.overview;
    const status = monitoringState.endpointStatus;
    // Per status the server sends that status' own top list, so endpoints outside the overall top list show up too
    const rows = status == null ? data.topEndpoints : (data.topEndpointsByStatus[status] || []);

    document.getElementById('mon-endpoint-filter').textContent = status == null ? '' : `(nur ${status})`;
    document.getElementById('mon-endpoint-reset').classList.toggle('hidden', status == null);

    const endpoints = document.getElementById('mon-endpoint-rows');
    endpoints.innerHTML = '';
    if (rows.length === 0) {
        const row = el('tr');
        const cell = el('td', 'admin-muted', 'Keine fehlgeschlagenen Requests.');
        cell.colSpan = 4;
        row.appendChild(cell);
        endpoints.appendChild(row);
        return;
    }
    rows.forEach((endpoint) => {
        const row = el('tr', 'mon-clickable');
        row.appendChild(el('td', 'mon-mono', `${endpoint.method || ''} ${endpoint.endpoint}`));
        row.appendChild(el('td', null, endpoint.statuses.map((s) => `${s.status}×${s.count}`).join(', ')));
        row.appendChild(el('td', null, formatNumber(endpoint.count)));
        row.appendChild(el('td', null, formatDateTime(endpoint.lastSeen)));
        // Endpoint patterns ("/groups/{id}") aren't searchable, the concrete path text is close enough.
        row.addEventListener('click', () => filterExplorer({ kind: 'REQUEST', q: endpoint.endpoint.split('{')[0] }));
        endpoints.appendChild(row);
    });
}

function renderEndpointUsage() {
    const tbody = document.getElementById('mon-usage-rows');
    tbody.innerHTML = '';
    const search = endpointUsageState.search.toLowerCase();
    const dir = endpointUsageState.sortAsc ? 1 : -1;
    const key = endpointUsageState.sortKey;

    const rows = endpointUsageState.endpoints
        .filter((e) => !search || `${e.method} ${e.endpoint}`.toLowerCase().includes(search))
        .sort((a, b) => (typeof a[key] === 'string' ? a[key].localeCompare(b[key]) : a[key] - b[key]) * dir);

    if (rows.length === 0) {
        const row = el('tr');
        const cell = el('td', 'admin-muted', 'Keine Aufrufe in diesem Zeitraum.');
        cell.colSpan = 5;
        row.appendChild(cell);
        tbody.appendChild(row);
        return;
    }

    rows.forEach((usage) => {
        const row = el('tr');
        row.appendChild(el('td', 'mon-mono', `${usage.method} ${usage.endpoint}`));
        row.appendChild(el('td', null, formatNumber(usage.count)));
        row.appendChild(el('td', null, usage.failed > 0 ? formatNumber(usage.failed) : '—'));
        row.appendChild(el('td', null, usage.statuses.map((s) => `${s.status}×${formatNumber(s.count)}`).join(', ')));
        row.appendChild(el('td', null, formatDateTime(usage.lastHour)));
        tbody.appendChild(row);
    });
}

function renderErrorTypes() {
    const tbody = document.getElementById('mon-errors-rows');
    tbody.innerHTML = '';
    const search = monitoringState.errorsSearch.toLowerCase();
    const dir = monitoringState.sortAsc ? 1 : -1;
    const key = monitoringState.sortKey;

    const rows = monitoringState.errorTypes
        .filter((t) => !search || t.tag.toLowerCase().includes(search) || t.lastMessage.toLowerCase().includes(search))
        .sort((a, b) => (typeof a[key] === 'string' ? a[key].localeCompare(b[key]) : a[key] - b[key]) * dir);

    if (rows.length === 0) {
        const row = el('tr');
        const cell = el('td', 'admin-muted', 'Keine Fehler in diesem Zeitraum.');
        cell.colSpan = 6;
        row.appendChild(cell);
        tbody.appendChild(row);
        return;
    }

    rows.forEach((type) => {
        const row = el('tr', 'mon-clickable');
        const tagCell = el('td');
        tagCell.appendChild(el('div', 'mon-mono', type.tag));
        tagCell.appendChild(el('div', 'admin-muted mon-sub', type.lastMessage));
        row.appendChild(tagCell);

        const levelCell = el('td');
        levelCell.appendChild(levelBadge(type.level));
        row.appendChild(levelCell);
        row.appendChild(el('td', 'mon-count', formatNumber(type.count)));
        row.appendChild(el('td', null, formatNumber(type.affectedUsers)));
        row.appendChild(el('td', null, formatDateTime(type.lastSeen)));

        const chartCell = el('td', 'mon-spark-cell');
        const chart = el('div', 'mon-chart mon-spark');
        chartCell.appendChild(chart);
        row.appendChild(chartCell);
        tbody.appendChild(row);
        renderColumnChart(chart, monitoringState.errorsAxis, [
            { name: type.tag, color: type.level === 'ERROR' ? MON_COLORS.error : MON_COLORS.warn, values: type.series },
        ], { mini: true });

        row.addEventListener('click', () => filterExplorer({ tag: type.tag, kind: 'LOG' }));
    });
}

/** Server status charts: one panel per metric group. */
const METRIC_PANELS = [
    { title: 'Heap (MB)', stacked: false, series: [['heapUsedMb', 'Belegt', MON_COLORS.info, 'gauges'], ['heapMaxMb', 'Max', MON_COLORS.gray, 'gauges']] },
    { title: 'CPU (%)', stacked: false, digits: 1, series: [['processCpuPercent', 'Prozess', MON_COLORS.ok, 'gauges'], ['systemCpuPercent', 'System', MON_COLORS.gray, 'gauges']] },
    { title: 'Threads', series: [['threadCount', 'Threads', MON_COLORS.info, 'gauges']] },
    { title: 'Socket-Verbindungen', stacked: false, series: [['socketConnections', 'Verbindungen', MON_COLORS.ok, 'gauges'], ['socketUsers', 'Nutzer', MON_COLORS.info, 'gauges']] },
    { title: 'Push FCM', series: [['fcmSent', 'Gesendet', MON_COLORS.ok, 'counters'], ['fcmFailed', 'Fehlgeschlagen', MON_COLORS.error, 'counters']] },
    { title: 'Push APNs', series: [['apnsSent', 'Gesendet', MON_COLORS.ok, 'counters'], ['apnsFailed', 'Fehlgeschlagen', MON_COLORS.error, 'counters']] },
    { title: 'E-Mails', series: [['emailsSent', 'Gesendet', MON_COLORS.ok, 'counters'], ['emailsFailed', 'Fehlgeschlagen', MON_COLORS.error, 'counters']] },
    { title: 'Nachrichten', series: [['messagesSent', 'Nachrichten', MON_COLORS.ok, 'counters']] },
    { title: 'Logins & neue Accounts', stacked: false, series: [['logins', 'Logins', MON_COLORS.info, 'counters'], ['newAccounts', 'Neue Accounts', MON_COLORS.purple, 'counters']] },
    { title: 'Mongo / Redis Ping (ms)', stacked: false, digits: 1, series: [['mongoPingMs', 'Mongo', MON_COLORS.ok, 'gauges'], ['redisPingMs', 'Redis', MON_COLORS.error, 'gauges']] },
    { title: 'GC-Zeit (ms)', series: [['gcTimeMs', 'GC', MON_COLORS.warn, 'counters']] },
    { title: 'Verworfene Log-Zeilen (Limit)', series: [['logsDropped', 'Verworfen', MON_COLORS.error, 'counters']] },
    { title: 'Aktive Geräte', devices: true },
];

const DEVICE_COLORS = [MON_COLORS.ok, MON_COLORS.info, MON_COLORS.purple, MON_COLORS.warn, MON_COLORS.gray];

function renderMetrics(data) {
    const grid = document.getElementById('mon-metrics');
    grid.querySelectorAll('.mon-chart').forEach((chart) => chartResizeObserver.unobserve(chart));
    grid.innerHTML = '';
    METRIC_PANELS.forEach((panel) => {
        const box = el('div', 'mon-panel');
        box.appendChild(el('div', 'mon-panel-title', panel.title));
        const chart = el('div', 'mon-chart');
        box.appendChild(chart);
        grid.appendChild(box);

        const series = panel.devices
            ? Object.entries(data.activeDevices).map(([type, values], i) => ({
                name: type, color: DEVICE_COLORS[i % DEVICE_COLORS.length], values,
            }))
            : panel.series.map(([key, name, color, group]) => ({
                // A failed ping is stored as -1 - not a value to draw.
                name, color, values: data[group][key].map((v) => (v != null && v < 0 ? null : v)),
            }));
        renderColumnChart(chart, data.axis, series, {
            height: 160,
            stacked: panel.stacked !== false,
            format: (v) => formatNumber(v, panel.digits || 0),
        });
    });
}

function levelBadge(level) {
    return el('span', `admin-badge mon-level-${level.toLowerCase()}`, level);
}

function logRow(entry) {
    const row = el('tr', 'mon-clickable');
    row.appendChild(el('td', 'mon-nowrap', new Date(entry.timestamp).toLocaleString('de-DE')));

    const levelCell = el('td');
    levelCell.appendChild(levelBadge(entry.level));
    if (entry.kind === 'REQUEST') levelCell.appendChild(el('span', 'admin-badge admin-badge-offline mon-kind', `HTTP ${entry.status}`));
    row.appendChild(levelCell);

    row.appendChild(el('td', 'mon-mono', entry.tag || ''));

    const userCell = el('td', 'admin-user-cell');
    userCell.appendChild(el('span', null, entry.username || '—'));
    if (entry.userId) userCell.appendChild(el('span', 'admin-user-id', entry.userId));
    row.appendChild(userCell);

    const messageCell = el('td', 'admin-log-message', entry.message);
    if (entry.path && entry.kind !== 'REQUEST') messageCell.appendChild(el('div', 'admin-muted mon-sub', `${entry.method} ${entry.path}`));
    row.appendChild(messageCell);

    const requestCell = el('td');
    if (entry.requestId) {
        const link = el('button', 'mon-link', entry.requestId);
        link.type = 'button';
        link.addEventListener('click', (e) => {
            e.stopPropagation();
            openTrace(entry.requestId);
        });
        requestCell.appendChild(link);
    }
    row.appendChild(requestCell);

    // Click toggles the stack trace (if any) right below the row.
    row.addEventListener('click', () => {
        const next = row.nextElementSibling;
        if (next?.classList.contains('mon-detail-row')) {
            next.remove();
            return;
        }
        if (!entry.stackTrace) return;
        const detail = el('tr', 'mon-detail-row');
        const cell = el('td');
        cell.colSpan = 6;
        cell.appendChild(el('pre', 'mon-stack', entry.stackTrace));
        detail.appendChild(cell);
        row.after(detail);
    });
    return row;
}

function explorerParams() {
    return {
        range: monitoringState.range,
        levels: document.getElementById('mon-log-level').value,
        kind: document.getElementById('mon-log-kind').value,
        tag: document.getElementById('mon-log-tag').value.trim(),
        user: document.getElementById('mon-log-user').value.trim(),
        q: document.getElementById('mon-log-q').value.trim(),
    };
}

async function loadExplorer(reset) {
    if (reset) {
        monitoringState.logsPage = 0;
        document.getElementById('mon-log-rows').innerHTML = '';
    }
    const params = new URLSearchParams({ ...explorerParams(), page: monitoringState.logsPage, pageSize: '50' });
    const page = await adminFetchJson(`/chefdev/api/monitoring/logs?${params}`);
    const tbody = document.getElementById('mon-log-rows');
    page.entries.forEach((entry) => tbody.appendChild(logRow(entry)));
    document.getElementById('mon-log-load-more').classList.toggle('hidden', !page.moreEntries);
}

/** Jumps from a chart / table row to the explorer, filtered to it. */
function filterExplorer({ tag = '', kind = '', q = '' }) {
    document.getElementById('mon-log-tag').value = tag;
    document.getElementById('mon-log-kind').value = kind;
    document.getElementById('mon-log-q').value = q;
    document.getElementById('mon-log-level').value = '';
    document.getElementById('mon-log-user').value = '';
    loadExplorer(true);
    document.getElementById('mon-explorer').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

async function openTrace(requestId) {
    const entries = await adminFetchJson(`/chefdev/api/monitoring/trace/${encodeURIComponent(requestId)}`);
    document.getElementById('mon-trace-id').textContent = requestId;
    const box = document.getElementById('mon-trace-rows');
    box.innerHTML = '';
    entries.forEach((entry) => {
        const line = el('div', 'mon-trace-line');
        line.appendChild(el('span', 'admin-muted mon-nowrap', new Date(entry.timestamp).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 })));
        line.appendChild(levelBadge(entry.level));
        if (entry.tag) line.appendChild(el('span', 'mon-mono', entry.tag));
        line.appendChild(el('span', 'admin-log-message', entry.message));
        box.appendChild(line);
        if (entry.stackTrace) box.appendChild(el('pre', 'mon-stack', entry.stackTrace));
    });
    document.getElementById('mon-trace-modal').classList.remove('hidden');
}

document.getElementById('mon-trace-close')?.addEventListener('click', () => {
    document.getElementById('mon-trace-modal').classList.add('hidden');
});

async function loadMonitoring() {
    const range = monitoringState.range;
    const levels = document.getElementById('mon-errors-level').value;

    const [overview, errors, metrics, usage] = await Promise.all([
        adminFetchJson(`/chefdev/api/monitoring/overview?range=${range}`),
        adminFetchJson(`/chefdev/api/monitoring/errors?range=${range}&levels=${levels}`),
        adminFetchJson(`/chefdev/api/monitoring/metrics?range=${range}`),
        adminFetchJson(`/chefdev/api/monitoring/endpoints?range=${range}`),
    ]);

    renderOverview(overview);
    endpointUsageState.endpoints = usage.endpoints;
    // Counted per full hour, so the table can start earlier than the other panels
    document.getElementById('mon-usage-since').textContent = `(seit ${formatDateTime(usage.since)})`;
    renderEndpointUsage();
    monitoringState.errorTypes = errors.types;
    monitoringState.errorsAxis = errors.axis;
    renderErrorTypes();
    renderMetrics(metrics);
    await loadExplorer(true);

    document.getElementById('mon-updated').textContent =
        `Stand ${new Date().toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}`;
}

document.getElementById('mon-range')?.addEventListener('click', (e) => {
    const button = e.target.closest('button[data-range]');
    if (!button) return;
    monitoringState.range = button.dataset.range;
    document.querySelectorAll('#mon-range button').forEach((b) => b.classList.toggle('active', b === button));
    loadMonitoring();
});

document.getElementById('mon-refresh')?.addEventListener('click', () => loadMonitoring());
document.getElementById('mon-endpoint-reset')?.addEventListener('click', () => setEndpointStatusFilter(null));

// Server status charts as a grid or one full-width chart per row, remembered per browser
const METRICS_LAYOUT_KEY = 'chefdev.metricsLayout';

function applyMetricsLayout(layout) {
    document.getElementById('mon-metrics')?.classList.toggle('mon-grid-rows', layout === 'rows');
    document.querySelectorAll('#mon-metrics-layout button').forEach((b) => b.classList.toggle('active', b.dataset.layout === layout));
}

document.getElementById('mon-metrics-layout')?.addEventListener('click', (e) => {
    const button = e.target.closest('button[data-layout]');
    if (!button) return;
    applyMetricsLayout(button.dataset.layout);
    try {
        localStorage.setItem(METRICS_LAYOUT_KEY, button.dataset.layout);
    } catch (_) {
        // Storage blocked - the layout just isn't remembered
    }
});

try {
    applyMetricsLayout(localStorage.getItem(METRICS_LAYOUT_KEY) || 'grid');
} catch (_) {
    applyMetricsLayout('grid');
}
document.getElementById('mon-errors-level')?.addEventListener('change', () => loadMonitoring());
document.getElementById('mon-errors-search')?.addEventListener('input', (e) => {
    monitoringState.errorsSearch = e.target.value;
    renderErrorTypes();
});
attachSortableHeader('mon-errors-header', monitoringState, ERROR_SORT_DEFAULT_DIR, renderErrorTypes);
document.getElementById('mon-usage-search')?.addEventListener('input', (e) => {
    endpointUsageState.search = e.target.value;
    renderEndpointUsage();
});
attachSortableHeader('mon-usage-header', endpointUsageState, USAGE_SORT_DEFAULT_DIR, renderEndpointUsage);

['mon-log-level', 'mon-log-kind'].forEach((id) => {
    document.getElementById(id)?.addEventListener('change', () => loadExplorer(true));
});

// Text filters reload after typing stops instead of on every key.
let explorerTypingTimer = null;
['mon-log-tag', 'mon-log-user', 'mon-log-q'].forEach((id) => {
    document.getElementById(id)?.addEventListener('input', () => {
        clearTimeout(explorerTypingTimer);
        explorerTypingTimer = setTimeout(() => loadExplorer(true), 400);
    });
});

document.getElementById('mon-log-load-more')?.addEventListener('click', () => {
    monitoringState.logsPage += 1;
    loadExplorer(false);
});

/* ---------------------------------------------------------------------- */
/* Server (update flag)                                                   */
/* ---------------------------------------------------------------------- */

// The buttons only set / clear a flag in Redis; the host's update script does the build and the
// container swap. Both endpoints answer with an empty body, hence adminFetch instead of adminFetchJson.
async function setServerUpdateFlag(method) {
    const messageEl = document.getElementById('server-message');
    messageEl.classList.remove('admin-form-error');
    messageEl.textContent = '';
    try {
        const response = await adminFetch('/chefdev/api/server/update', { method });
        if (response.status === 404) throw new Error('Keine Admin-Berechtigung.');
        if (!response.ok) throw new Error(`Anfrage fehlgeschlagen (${response.status})`);
        messageEl.textContent = method === 'POST'
            ? 'Update-Flag gesetzt. Das Update-Skript am Host holt es beim nächsten Lauf ab.'
            : 'Update-Flag entfernt.';
    } catch (e) {
        if (e instanceof SessionExpiredError) return;
        messageEl.classList.add('admin-form-error');
        messageEl.textContent = e.message || 'Anfrage fehlgeschlagen.';
    }
}

document.getElementById('server-update-button')?.addEventListener('click', () => {
    if (!confirm('Server-Update anfordern?\n\nDas Update-Skript am Host baut den aktuellen main-Stand von GitHub und startet den Server neu (kurzer Ausfall für alle Nutzer).')) return;
    setServerUpdateFlag('POST');
});
document.getElementById('server-cancel-button')?.addEventListener('click', () => setServerUpdateFlag('DELETE'));

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
