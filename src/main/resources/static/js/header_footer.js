// Create header.html content as a string
const headerHTML = `
            <header>
                <div class="logo">
                    <img src="/web_images/Icon.png" alt="Schneaggchat Icon" style="height:1em; vertical-align:middle;">
                    <span>Schneaggchat</span>
                </div>
                <nav class="nav-links">
                    <a href="/" data-i18n="nav_home">Home</a>
                    <a href="/stats.html" data-i18n="nav_stats">Stats</a>
                    <a href="/privacypolicy.html" data-i18n="nav_privacy">Datenschutz</a>
                    <a href="/donations.html" data-i18n="nav_donate">Spenden</a>
                    <div class="lang-select-wrap">
                        <select id="lang-select" aria-label="Sprache">
                            <option value="de">🇩🇪 Deutsch</option>
                            <option value="de-at">🇦🇹 Vorarlbergerisch</option>
                            <option value="en">🇬🇧 English</option>
                        </select>
                    </div>
                </nav>
                <button class="mobile-toggle">
                    <i class="fas fa-bars"></i>
                </button>
            </header>
        `;

// Create footer.html content as a string
const footerHTML = `
            <footer>
                <div class="footer-columns">
                    <div class="footer-column">
                        <h4 data-i18n="footer_nav_heading">Navigation</h4>
                        <a href="/" data-i18n="nav_home">Home</a>
                        <a href="/stats.html" data-i18n="nav_stats">Stats</a>
                    </div>
                    <div class="footer-column">
                        <h4 data-i18n="footer_legal_heading">Rechtliches</h4>
                        <a href="/privacypolicy.html" data-i18n="nav_privacy">Datenschutz</a>
                    </div>
                    <div class="footer-column">
                        <h4 data-i18n="footer_support_heading">Support</h4>
                        <a href="/donations.html" data-i18n="nav_donate">Spenden</a>
                        <a href="/delete_account.html" data-i18n="footer_delete_account">Account löschen</a>
                        <a href="/reset_password.html" data-i18n="footer_reset_password">Passwort zurücksetzen</a>
                    </div>
                </div>
            </footer>
        `;

// Bottom language bar - shown to every visitor until dismissed (closed, or
// a language picked), offering Deutsch, Vorarlbergerisch and English.
const langBarHTML = `
            <div id="lang-bar" class="lang-bar">
                <p class="lang-bar-text">Wir verwenden keine Cookies, dafür kannst du hier auf Vorarlbergerisch umschalten.</p>
                <div class="lang-bar-buttons">
                    <button type="button" class="lang-bar-btn" data-lang="de">Deutsch</button>
                    <button type="button" class="lang-bar-btn" data-lang="de-at">Vorarlbergerisch</button>
                    <button type="button" class="lang-bar-btn" data-lang="en">English</button>
                </div>
                <button type="button" class="lang-bar-close" aria-label="Schließen">
                    <i class="fas fa-xmark"></i>
                </button>
            </div>
        `;

// Inject header and footer into containers
document.getElementById('header-container').innerHTML = headerHTML;
document.getElementById('footer-container').innerHTML = footerHTML;

/* ---------------------------------------------------------------------- */
/* i18n: loads /i18n/strings-<lang>.xml (Android strings.xml style) and   */
/* applies it to every element carrying a data-i18n="key" attribute.      */
/* Every page already contains its default German copy inline, so a      */
/* missing key (e.g. legal text not yet translated) simply falls back to */
/* whatever German text already sits in the DOM - never a blank page.    */
/* ---------------------------------------------------------------------- */

const LANG_STORAGE_KEY = 'schneaggchat-lang';
const LANG_BAR_DISMISSED_KEY = 'schneaggchat-lang-bar-dismissed';
const SUPPORTED_LANGS = ['de', 'de-at', 'en'];

window.__schneaggchatI18n = {};

function getCurrentLang() {
    const stored = localStorage.getItem(LANG_STORAGE_KEY);
    return SUPPORTED_LANGS.includes(stored) ? stored : 'de';
}

async function loadTranslations(lang) {
    try {
        const response = await fetch(`/i18n/strings-${lang}.xml`);
        if (!response.ok) return {};

        const xmlText = await response.text();
        const xmlDoc = new DOMParser().parseFromString(xmlText, 'application/xml');

        if (xmlDoc.querySelector('parsererror')) return {};

        const map = {};
        xmlDoc.querySelectorAll('string[name]').forEach((node) => {
            map[node.getAttribute('name')] = node.innerHTML.trim();
        });
        return map;
    } catch (e) {
        console.warn('Could not load translations for', lang, e);
        return {};
    }
}

function applyTranslations(map) {
    document.querySelectorAll('[data-i18n]').forEach((el) => {
        const key = el.getAttribute('data-i18n');
        if (map[key] !== undefined) {
            el.innerHTML = map[key];
        }
    });
}

async function applyLanguage(lang) {
    if (!SUPPORTED_LANGS.includes(lang)) lang = 'de';
    document.documentElement.lang = lang === 'de-at' ? 'de-AT' : lang;

    const map = await loadTranslations(lang);
    window.__schneaggchatI18n = map;
    applyTranslations(map);

    // Lets pages with server-rendered content (e.g. donations, which formats dates per locale)
    // re-render themselves whenever the language changes, including on initial page load.
    document.dispatchEvent(new CustomEvent('schneaggchat:languagechanged', { detail: { lang } }));
}

// Only called when the visitor actively picks a language (lang-bar button),
// as opposed to applyLanguage() which also runs on every normal page load.
async function chooseLanguage(lang) {
    localStorage.setItem(LANG_STORAGE_KEY, lang);
    await applyLanguage(lang);
}

function initLangBar() {
    if (localStorage.getItem(LANG_BAR_DISMISSED_KEY) === 'true') return;
    if (localStorage.getItem(LANG_STORAGE_KEY)) return; // already chose once

    document.body.insertAdjacentHTML('beforeend', langBarHTML);
    const bar = document.getElementById('lang-bar');

    bar.querySelectorAll('.lang-bar-btn').forEach((btn) => {
        btn.addEventListener('click', () => {
            localStorage.setItem(LANG_BAR_DISMISSED_KEY, 'true');
            chooseLanguage(btn.dataset.lang);
            const select = document.getElementById('lang-select');
            if (select) select.value = btn.dataset.lang;
            bar.remove();
        });
    });

    bar.querySelector('.lang-bar-close').addEventListener('click', () => {
        localStorage.setItem(LANG_BAR_DISMISSED_KEY, 'true');
        bar.remove();
    });

    requestAnimationFrame(() => bar.classList.add('visible'));
}

// Persistent header dropdown - lets a visitor change language any time,
// including after the bottom bar has been dismissed.
function initLangSelect() {
    const select = document.getElementById('lang-select');
    if (!select) return;

    select.value = getCurrentLang();

    select.addEventListener('change', () => {
        localStorage.setItem(LANG_BAR_DISMISSED_KEY, 'true');
        document.getElementById('lang-bar')?.remove();
        chooseLanguage(select.value);
    });
}

/* ---------------------------------------------------------------------- */

// Initialize functionality after components are injected
function initPage() {
    // Logo click handler
    document.querySelector('.logo')?.addEventListener('click', () => {
        window.location.href = '/';
    });

    // Mobile menu toggle - UNIVERSAL VERSION
    const mobileToggle = document.querySelector('.mobile-toggle');
    const navLinks = document.querySelector('.nav-links');

    if (mobileToggle && navLinks) {
        mobileToggle.addEventListener('click', function (e) {
            e.stopPropagation(); // Prevent click from bubbling to document
            navLinks.classList.toggle('active');
        });

        // Close menu when clicking outside
        document.addEventListener('click', function (e) {
            if (navLinks.classList.contains('active') && !navLinks.contains(e.target)) {
                navLinks.classList.remove('active');
            }
        });
    }

    // Universal scroll handler
    window.addEventListener('scroll', function () {
        const header = document.querySelector('header');
        if (header) {
            header.classList.toggle('scrolled', window.scrollY > 50);
        }
    });

    applyLanguage(getCurrentLang());
    initLangSelect();
    initLangBar();
}

// Initialize when elements are ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initPage);
} else {
    initPage(); // In case DOM is already ready
}
