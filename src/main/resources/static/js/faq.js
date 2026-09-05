// FAQ rendering. The entries live in the database and are managed from the admin panel
// (/chefdev.html) - this file only fetches and renders them.
//
// Every entry ships all three languages at once, so switching language re-renders from what is
// already loaded. An entry without a translation falls back to German, matching how a missing key
// in strings-<lang>.xml falls back to the German copy sitting in the HTML.

// German headings, used when strings-<lang>.xml has no key for the category (e.g. de-at, whose
// file is hand-written and only covers what has actually been translated).
const CATEGORY_FALLBACK_LABELS = {
    GENERAL: 'Allgemein',
    ACCOUNT: 'Account',
    CHATS: 'Chats',
    NOTIFICATIONS: 'Benachrichtigungen',
    MAP: 'Karte',
    PRIVACY: 'Datenschutz',
    TECHNICAL: 'Technisches',
};

let faqEntries = null;
let searchTerm = '';
const openEntryIds = new Set();

function translationForLang(entry, lang) {
    if (lang === 'de-at') return entry.austrian || entry.german;
    if (lang === 'en') return entry.english || entry.german;
    return entry.german;
}

function categoryLabel(category) {
    const translated = window.__schneaggchatI18n[`faq_category_${category.toLowerCase()}`];
    return translated || CATEGORY_FALLBACK_LABELS[category] || category;
}

// A term matches when every word of it appears in the question or the answer, so "email spam"
// finds an entry mentioning both regardless of their order.
function matchesSearch(text) {
    if (!searchTerm) return true;

    const haystack = `${text.question} ${text.answer}`.toLowerCase();
    return searchTerm.toLowerCase().split(/\s+/).every((word) => haystack.includes(word));
}

function searchPlaceholder() {
    return window.__schneaggchatI18n['faq_search_placeholder'] || 'Frage suchen ...';
}

// Question and answer are admin-entered database content, so both are set via textContent - the
// FAQ never builds markup out of them.
function buildEntry(entry, lang) {
    const text = translationForLang(entry, lang);

    const item = document.createElement('details');
    item.className = 'faq-item';
    // While searching, every hit is shown open - the match may sit in the answer. That open state
    // belongs to the search, so it is not remembered once the search is cleared.
    item.open = searchTerm ? true : openEntryIds.has(entry.id);
    item.addEventListener('toggle', () => {
        if (searchTerm) return;
        if (item.open) openEntryIds.add(entry.id);
        else openEntryIds.delete(entry.id);
    });

    const summary = document.createElement('summary');
    summary.className = 'faq-question';
    const questionText = document.createElement('span');
    questionText.textContent = text.question;
    summary.appendChild(questionText);
    const chevron = document.createElement('i');
    chevron.className = 'fa-solid fa-chevron-down faq-chevron';
    summary.appendChild(chevron);
    item.appendChild(summary);

    const answer = document.createElement('p');
    answer.className = 'faq-answer';
    answer.textContent = text.answer;
    item.appendChild(answer);

    return item;
}

function renderFaq(lang) {
    const container = document.getElementById('faq-categories');
    const emptyHint = document.getElementById('faq-empty');
    const noResultsHint = document.getElementById('faq-no-results');
    const searchInput = document.getElementById('faq-search-input');
    if (!container || faqEntries === null) return;

    searchInput.placeholder = searchPlaceholder();
    document.getElementById('faq-search-clear').classList.toggle('hidden', !searchTerm);

    const matches = faqEntries.filter((entry) => matchesSearch(translationForLang(entry, lang)));

    container.innerHTML = '';
    emptyHint.classList.toggle('hidden', faqEntries.length > 0);
    noResultsHint.classList.toggle('hidden', faqEntries.length === 0 || matches.length > 0);

    // Entries arrive already ordered by category and position, so a category block ends as soon as
    // the next entry carries a different category.
    let currentCategory = null;
    let currentList = null;

    matches.forEach((entry) => {
        if (entry.category !== currentCategory) {
            currentCategory = entry.category;

            const block = document.createElement('section');
            block.className = 'faq-category';

            const heading = document.createElement('h2');
            heading.className = 'faq-category-title';
            heading.textContent = categoryLabel(entry.category);
            block.appendChild(heading);

            currentList = document.createElement('div');
            currentList.className = 'faq-list';
            block.appendChild(currentList);

            container.appendChild(block);
        }

        currentList.appendChild(buildEntry(entry, lang));
    });
}

async function loadAndRenderFaq() {
    try {
        const response = await fetch('/public/faq');
        if (!response.ok) return;

        const data = await response.json();
        faqEntries = data.entries;

        renderFaq(getCurrentLang());
    } catch (e) {
        console.warn('Could not load FAQ', e);
    }
}

function setUpSearch() {
    const input = document.getElementById('faq-search-input');
    const clearButton = document.getElementById('faq-search-clear');

    input.addEventListener('input', () => {
        searchTerm = input.value.trim();
        renderFaq(getCurrentLang());
    });

    clearButton.addEventListener('click', () => {
        input.value = '';
        searchTerm = '';
        renderFaq(getCurrentLang());
        input.focus();
    });
}

document.addEventListener('schneaggchat:languagechanged', (event) => {
    renderFaq(event.detail.lang);
});

function initFaqPage() {
    setUpSearch();
    loadAndRenderFaq();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initFaqPage);
} else {
    initFaqPage();
}
