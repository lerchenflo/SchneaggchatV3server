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
    MAP: 'Karte',
    PRIVACY: 'Datenschutz',
    TECHNICAL: 'Technisches',
};

let faqEntries = null;
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

// Question and answer are admin-entered database content, so both are set via textContent - the
// FAQ never builds markup out of them.
function buildEntry(entry, lang) {
    const text = translationForLang(entry, lang);

    const item = document.createElement('details');
    item.className = 'faq-item';
    item.open = openEntryIds.has(entry.id);
    item.addEventListener('toggle', () => {
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
    if (!container || faqEntries === null) return;

    container.innerHTML = '';
    emptyHint.classList.toggle('hidden', faqEntries.length > 0);

    // Entries arrive already ordered by category and position, so a category block ends as soon as
    // the next entry carries a different category.
    let currentCategory = null;
    let currentList = null;

    faqEntries.forEach((entry) => {
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

document.addEventListener('schneaggchat:languagechanged', (event) => {
    renderFaq(event.detail.lang);
});

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', loadAndRenderFaq);
} else {
    loadAndRenderFaq();
}
