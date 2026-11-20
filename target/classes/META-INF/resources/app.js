'use strict';

const state = { treatments: [], therapists: [], owners: [], ducks: [] };
const view  = document.getElementById('view');


// ---------- helpers ----------
function el(tag, props, ...kids) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(props || {})) {
        if (value === false || value == null) { continue; }
        if (key === 'class')           { node.className = value; }
        else if (key === 'text')       { node.textContent = value; }
        else if (key.startsWith('on')) { node.addEventListener(key.slice(2), value); }
        else if (key === 'value' || key === 'checked' || key === 'disabled') { node[key] = value; }
        else                           { node.setAttribute(key, value === true ? '' : value); }
    }
    node.append(...kids.flat().filter(kid => kid != null && kid !== false));
    return node;
}

async function api(path, options) {
    const response = await fetch('/api' + path, Object.assign({ headers: { 'Content-Type': 'application/json' } }, options));
    const data     = await response.json().catch(() => null);
    if (!response.ok) { throw new Error((data && data.error) || 'The clinic could not handle that request (HTTP ' + response.status + ').'); }
    return data;
}

const pad       = n => String(n).padStart(2, '0');
const localNow  = () => { const d = new Date(); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes()); };
const fmtDay    = iso => new Date(iso).toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
const fmtTime   = date => date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
const fmtMoney  = value => Number(value).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const dayKey    = iso => iso.slice(0, 10);
const statusLbl = { BOOKED: 'Booked', COMPLETED: 'Completed', CANCELLED: 'Cancelled' };

function option(value, text, selected) { return el('option', { value: value, selected: selected }, text); }

function errorBox(message) { return el('p', { class: 'error', role: 'alert' }, message); }

function heading(title, lede) { return el('div', { class: 'head' }, el('h2', { text: title }), lede && el('p', { class: 'lede', text: lede })); }


// ---------- shared pieces ----------
function treatmentLane(treatment, input) {
    const bar   = el('span', { class: 'bar', style: 'width:' + Math.min(100, Math.round(treatment.durationMinutes / 60 * 100)) + '%' });
    const body  = [el('span', { class: 'name', text: treatment.name }), el('span', { class: 'price', text: fmtMoney(treatment.price) }), el('span', { class: 'desc', text: treatment.description }), el('span', { class: 'mins', text: treatment.durationMinutes + ' minutes' }), bar];
    return input ? el('label', { class: 'lane-item pick' }, input, ...body) : el('div', { class: 'lane-item' }, ...body);
}

function appointmentRow(a) {
    const start = new Date(a.startTime);
    const end   = new Date(a.endTime);
    return el('li', { class: 'appt ' + a.status },
        el('div', { class: 'time' }, fmtTime(start) + ' to ' + fmtTime(end), el('small', { text: a.appointmentNumber })),
        el('div', null, el('div', { class: 'what', text: a.treatmentName + ' for ' + a.duckName }), el('div', { class: 'who', text: 'Owner ' + a.ownerName + ', with ' + a.therapistName + ', ' + fmtMoney(a.price) })),
        el('span', { class: 'pill ' + a.status, text: statusLbl[a.status] || a.status }),
        a.notes && el('div', { class: 'notes', text: a.notes }));
}

function groupedAppointments(list) {
    const days = new Map();
    for (const a of list) {
        const key = dayKey(a.startTime);
        if (!days.has(key)) { days.set(key, []); }
        days.get(key).push(a);
    }
    return [...days.entries()].map(([key, items]) => el('section', { class: 'day' }, el('h3', { text: fmtDay(items[0].startTime) }), el('ul', { class: 'appts' }, items.map(appointmentRow))));
}


// ---------- view: treatments ----------
function renderTreatments() {
    view.replaceChildren(
        heading('Treatments', 'Every session is one-to-one with a therapist. The bar shows how long it takes compared to a full hour.'),
        el('ul', { class: 'lanes' }, state.treatments.map(t => el('li', null, treatmentLane(t)))),
        el('h2', { class: 'section', text: 'Our therapists' }),
        el('ul', { class: 'team' }, state.therapists.map(t => el('li', null, el('strong', { text: t.name }), el('span', { text: t.specialty }), el('span', { text: t.city })))));
}


// ---------- view: book ----------
function renderBook(prefill) {
    prefill = prefill || {};
    const errorSlot = el('div');
    const ownerSel  = el('select', { id: 'owner', required: true });
    const duckSel   = el('select', { id: 'duck', required: true, disabled: true });
    const therapist = el('select', { id: 'therapist', required: true });
    const when      = el('input', { id: 'when', type: 'datetime-local', required: true, min: localNow(), step: '900' });
    const notes     = el('textarea', { id: 'notes', maxlength: '500', placeholder: 'Anything the therapist should know, for example a sore left foot' });
    const submit    = el('button', { class: 'btn', type: 'submit', text: 'Book appointment' });
    const ticket    = el('aside', { class: 'ticket', 'aria-live': 'polite' });
    const picks     = state.treatments.map(t => el('input', { type: 'radio', name: 'treatment', value: t.id, required: true }));

    ownerSel.append(option('', 'Choose an owner'), ...state.owners.map(o => option(o.id, o.firstName + ' ' + o.lastName)));
    therapist.append(option('', 'Choose a therapist'), ...state.therapists.map(t => option(t.id, t.name + ' (' + t.specialty + ')')));

    function fillDucks() {
        const ducks = state.ducks.filter(d => String(d.ownerId) === ownerSel.value);
        duckSel.replaceChildren(option('', ownerSel.value ? 'Choose a duck' : 'Choose an owner first'), ...ducks.map(d => option(d.id, d.name + ' (' + d.breed + ')')));
        duckSel.disabled = !ownerSel.value;
    }

    function updateTicket() {
        const duck      = state.ducks.find(d => String(d.id) === duckSel.value);
        const treatment = state.treatments.find(t => String(t.id) === (picks.find(p => p.checked) || {}).value);
        const doctor    = state.therapists.find(t => String(t.id) === therapist.value);
        const start     = when.value ? new Date(when.value) : null;
        const end       = start && treatment ? new Date(start.getTime() + treatment.durationMinutes * 60000) : null;
        const row       = (label, text) => [el('dt', { text: label }), el('dd', { class: text ? '' : 'empty', text: text || 'Not chosen yet' })];
        ticket.replaceChildren(
            el('h3', { text: 'Your session' }),
            el('dl', null, ...row('Duck', duck && duck.name + ', owner ' + duck.ownerName), ...row('Treatment', treatment && treatment.name), ...row('Therapist', doctor && doctor.name), ...row('When', start && fmtDay(start) + ', ' + fmtTime(start) + (end ? ' to ' + fmtTime(end) : ''))),
            el('div', { class: 'total' }, el('span', { text: 'Price' }), el('span', { text: treatment ? fmtMoney(treatment.price) : '-' })));
    }

    ownerSel.addEventListener('change', () => { fillDucks(); updateTicket(); });
    [duckSel, therapist, when].forEach(control => control.addEventListener('change', updateTicket));
    picks.forEach(pick => pick.addEventListener('change', updateTicket));

    const form = el('form', { class: 'panel', novalidate: false, onsubmit: async event => {
        event.preventDefault();
        errorSlot.replaceChildren();
        submit.disabled = true;
        try {
            const created = await api('/appointments', { method: 'POST', body: JSON.stringify({ duckId: Number(duckSel.value), treatmentId: Number(picks.find(p => p.checked).value), therapistId: Number(therapist.value), startTime: when.value, notes: notes.value }) });
            renderConfirmation(created);
        } catch (e) {
            errorSlot.replaceChildren(errorBox(e.message));
            submit.disabled = false;
            errorSlot.scrollIntoView({ block: 'nearest' });
        }
    } },
        errorSlot,
        el('div', { class: 'row' },
            el('div', { class: 'field' }, el('label', { for: 'owner', text: 'Owner' }), ownerSel),
            el('div', { class: 'field' }, el('label', { for: 'duck', text: 'Duck' }), duckSel)),
        el('fieldset', { class: 'field' }, el('legend', { text: 'Treatment' }), el('div', { class: 'lanes' }, state.treatments.map((t, i) => treatmentLane(t, picks[i])))),
        el('div', { class: 'row' },
            el('div', { class: 'field' }, el('label', { for: 'therapist', text: 'Therapist' }), therapist),
            el('div', { class: 'field' }, el('label', { for: 'when', text: 'Date and time' }), when, el('p', { class: 'hint', text: 'Monday to Friday, 08:00 to 18:00' }))),
        el('div', { class: 'field' }, el('label', { for: 'notes', text: 'Notes (optional)' }), notes),
        submit);

    view.replaceChildren(heading('Book a session', 'Pick the duck, the treatment and a time. The clinic checks that the therapist and the duck are free.'), el('div', { class: 'book' }, form, ticket));
    fillDucks();
    updateTicket();
}

function renderConfirmation(a) {
    const start = new Date(a.startTime);
    view.replaceChildren(el('div', { class: 'panel done' },
        el('h2', { text: 'Booked' }),
        el('p', { class: 'number', text: a.appointmentNumber }),
        el('p', { class: 'lede', text: a.duckName + ' has ' + a.treatmentName + ' with ' + a.therapistName + ' on ' + fmtDay(a.startTime) + ' at ' + fmtTime(start) + '.' }),
        el('div', { class: 'actions' },
            el('button', { class: 'btn', type: 'button', onclick: () => renderBook(), text: 'Book another session' }),
            el('a', { class: 'btn quiet', href: '#appointments?owner=' + a.ownerId, text: 'View appointments for ' + a.ownerName }))));
    window.scrollTo(0, 0);
}


// ---------- view: appointments ----------
function renderAppointments(params) {
    let mode = params.get('duck') ? 'duck' : params.get('number') ? 'number' : 'owner';
    const results  = el('div', { 'aria-live': 'polite' });
    const picker   = el('div', { class: 'field' });
    const cancelled = el('input', { type: 'checkbox', id: 'cancelled' });
    const segment  = el('div', { class: 'seg', role: 'radiogroup', 'aria-label': 'Find appointments by' }, [['owner', 'By owner'], ['duck', 'By duck'], ['number', 'By number']].map(([value, text]) => el('label', null, el('input', { type: 'radio', name: 'mode', value: value, checked: value === mode, onchange: () => { mode = value; buildPicker(); } }), el('span', { text: text }))));

    async function load() {
        const value = picker.querySelector('select, input') && picker.querySelector('select, input').value;
        if (!value) { results.replaceChildren(); return; }
        results.replaceChildren(el('p', { class: 'loading', text: 'Looking up appointments…' }));
        try {
            if (mode === 'number') {
                const id = Number(String(value).replace(/\D/g, ''));
                if (!id) { throw new Error('Enter an appointment number like APT-00001 or just 1.'); }
                const one = await api('/appointments/' + id);
                results.replaceChildren(el('ul', { class: 'appts' }, appointmentRow(one)));
                return;
            }
            const path = (mode === 'owner' ? '/owners/' : '/ducks/') + value + '/appointments?includeCancelled=' + cancelled.checked;
            const list = await api(path);
            if (list.count === 0) {
                const name = mode === 'owner' ? state.owners.find(o => String(o.id) === value) : state.ducks.find(d => String(d.id) === value);
                results.replaceChildren(el('div', { class: 'empty-state' }, el('p', { text: 'No appointments yet for ' + (mode === 'owner' ? name.firstName + ' ' + name.lastName : name.name) + '.' }), el('a', { class: 'btn', href: '#book', text: 'Book a session' })));
            } else {
                results.replaceChildren(...groupedAppointments(list.appointments));
            }
        } catch (e) {
            results.replaceChildren(errorBox(e.message));
        }
    }

    function buildPicker() {
        let control;
        if (mode === 'owner') {
            control = el('select', { id: 'pick' }, option('', 'Choose an owner'), ...state.owners.map(o => option(o.id, o.firstName + ' ' + o.lastName, String(o.id) === params.get('owner'))));
        } else if (mode === 'duck') {
            control = el('select', { id: 'pick' }, option('', 'Choose a duck'), ...state.ducks.map(d => option(d.id, d.name + ' (' + d.ownerName + ')', String(d.id) === params.get('duck'))));
        } else {
            control = el('input', { id: 'pick', type: 'text', placeholder: 'APT-00001', value: params.get('number') || '' });
            control.addEventListener('keydown', event => { if (event.key === 'Enter') { load(); } });
        }
        control.addEventListener('change', load);
        picker.replaceChildren(el('label', { for: 'pick', text: mode === 'number' ? 'Appointment number' : mode === 'owner' ? 'Owner' : 'Duck' }), control);
        cancelled.closest('.check').hidden = mode === 'number';
        results.replaceChildren();
        if (control.value) { load(); }
    }

    cancelled.addEventListener('change', load);
    const finder = el('div', { class: 'finder' }, segment, picker, el('label', { class: 'check' }, cancelled, 'Include cancelled'));
    view.replaceChildren(heading('Appointments', 'Find every session for an owner, for one duck, or open a single appointment by its number.'), finder, results);
    buildPicker();
}


// ---------- routing ----------
function route() {
    const [name, query] = (location.hash.slice(1) || 'book').split('?');
    const params        = new URLSearchParams(query || '');
    document.querySelectorAll('nav a').forEach(a => { if (a.dataset.tab === name) { a.setAttribute('aria-current', 'page'); } else { a.removeAttribute('aria-current'); } });
    if (name === 'treatments')        { renderTreatments(); }
    else if (name === 'appointments') { renderAppointments(params); }
    else                              { renderBook(); }
}

async function start() {
    try {
        const [treatments, therapists, owners, ducks] = await Promise.all([api('/treatments'), api('/therapists'), api('/owners'), api('/ducks')]);
        state.treatments = treatments.treatments;
        state.therapists = therapists.therapists;
        state.owners     = owners.owners;
        state.ducks      = ducks.ducks;
        window.addEventListener('hashchange', route);
        route();
    } catch (e) {
        view.replaceChildren(errorBox('The clinic data could not be loaded: ' + e.message + ' Reload the page to try again.'));
    }
}

start();
