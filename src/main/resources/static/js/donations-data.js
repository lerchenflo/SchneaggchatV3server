// Donations rendering. Donation data now lives in the database and is managed from the admin
// panel (/chefdev.html) - this file only fetches and renders it.

// Available icons for random selection - purely decorative, not persisted.
const iconOptions = [
    "fas fa-heart",
    "fas fa-star",
    "fas fa-rocket",
    "fas fa-gift",
    "fas fa-coffee",
    "fas fa-thumbs-up",
    "fas fa-hand-holding-heart",
    "fas fa-trophy",
    "fas fa-medal",
    "fas fa-crown",
    "fas fa-gem",
    "fas fa-fire"
];

function getRandomIcon() {
    return iconOptions[Math.floor(Math.random() * iconOptions.length)];
}

// Animate value function (copied/adapted from stats.html logic)
function animateValue(element, target) {
    let current = 0;
    const increment = target / 50; // 50 steps
    const timer = setInterval(() => {
        current += increment;
        if (current >= target) {
            element.textContent = `€${target.toFixed(2)}`;
            clearInterval(timer);
        } else {
            element.textContent = `€${current.toFixed(2)}`;
        }
    }, 20); // 20ms interval -> ~1 second total duration
}

// de-AT gives the same "27. Jänner 2026" wording the old hardcoded dates used.
const DATE_LOCALE_TAGS = { de: 'de-DE', 'de-at': 'de-AT', en: 'en-GB' };

function formatDonationDate(epochMillis, lang) {
    const tag = DATE_LOCALE_TAGS[lang] || 'de-DE';
    return new Intl.DateTimeFormat(tag, { day: 'numeric', month: 'long', year: 'numeric' }).format(new Date(epochMillis));
}

let latestDonations = null;
let latestTotalCents = 0;

// Render donations to the page. Donor name/message come from the database (admin-entered), so they
// are set via textContent, never innerHTML - the only markup built as a string here is the static
// icon element, which never contains donor-supplied data.
function renderDonations(lang) {
    const donationsGrid = document.querySelector('.donations-grid');
    const totalAmountElement = document.querySelector('.total-amount');
    if (!donationsGrid || latestDonations === null) return;

    donationsGrid.innerHTML = '';

    latestDonations.forEach((donation) => {
        const card = document.createElement('div');
        card.className = 'donation-card';

        const iconWrap = document.createElement('div');
        iconWrap.className = 'donation-icon';
        iconWrap.innerHTML = `<i class="${getRandomIcon()}"></i>`; // static, no donor data
        card.appendChild(iconWrap);

        const nameEl = document.createElement('h4');
        nameEl.className = 'donation-name';
        nameEl.textContent = donation.name;
        card.appendChild(nameEl);

        const amountEl = document.createElement('p');
        amountEl.className = 'donation-amount';
        amountEl.textContent = '€0.00';
        card.appendChild(amountEl);

        const dateEl = document.createElement('p');
        dateEl.className = 'donation-date';
        dateEl.textContent = formatDonationDate(donation.donatedAt, lang);
        card.appendChild(dateEl);

        if (donation.message) {
            const messageEl = document.createElement('p');
            messageEl.className = 'donation-message';
            messageEl.textContent = `"${donation.message}"`;
            card.appendChild(messageEl);
        }

        donationsGrid.appendChild(card);
        animateValue(amountEl, donation.amount);
    });

    if (totalAmountElement) {
        animateValue(totalAmountElement, latestTotalCents / 100);
    }
}

async function loadAndRenderDonations() {
    try {
        const response = await fetch('/public/donations');
        if (!response.ok) return;

        const data = await response.json();
        latestDonations = data.donations.map((d) => ({
            name: d.name,
            amount: d.amountCents / 100,
            donatedAt: d.donatedAt,
            message: d.message,
        }));
        latestTotalCents = data.totalCents;

        renderDonations(getCurrentLang());
    } catch (e) {
        console.warn('Could not load donations', e);
    }
}

document.addEventListener('schneaggchat:languagechanged', (event) => {
    renderDonations(event.detail.lang);
});

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', loadAndRenderDonations);
} else {
    loadAndRenderDonations();
}
