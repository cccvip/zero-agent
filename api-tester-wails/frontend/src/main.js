import {SendRequest} from '../wailsjs/go/main/App';

document.getElementById('sendBtn').addEventListener('click', send);

document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => switchTab(tab.dataset.tab));
});

async function send() {
    const btn = document.getElementById('sendBtn');
    btn.disabled = true;
    btn.textContent = 'Sending...';

    const headersText = document.getElementById('headers').value;
    const headers = {};
    headersText.split('\n').forEach(line => {
        const idx = line.indexOf(':');
        if (idx > 0) {
            headers[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
        }
    });

    const payload = {
        method: document.getElementById('method').value,
        url: document.getElementById('url').value.trim(),
        headers,
        body: document.getElementById('body').value
    };

    try {
        const data = await SendRequest(payload);
        showResponse(data);
    } catch (err) {
        showResponse({error: err.message || String(err)});
    } finally {
        btn.disabled = false;
        btn.textContent = 'Send';
    }
}

function showResponse(data) {
    document.getElementById('empty').style.display = 'none';
    document.getElementById('result').style.display = 'flex';

    const badge = document.getElementById('statusBadge');
    if (data.error) {
        badge.className = 'badge err';
        badge.textContent = 'Error';
        document.getElementById('duration').textContent = data.duration_ms ?? '-';
        document.getElementById('size').textContent = '0';
        document.getElementById('respBody').innerHTML = `<span class="error">${escapeHtml(data.error)}</span>`;
        document.getElementById('respHeaders').innerHTML = '';
        return;
    }

    badge.className = 'badge ' + (data.status >= 200 && data.status < 300 ? 'ok' : data.status < 400 ? 'warn' : 'err');
    badge.textContent = `${data.status} ${data.status_text}`;
    document.getElementById('duration').textContent = data.duration_ms;

    let body = data.body;
    try {
        body = JSON.stringify(JSON.parse(body), null, 2);
    } catch (e) { /* keep raw */ }
    document.getElementById('respBody').textContent = body;
    document.getElementById('size').textContent = new Blob([data.body]).size;

    const tbody = document.getElementById('respHeaders');
    tbody.innerHTML = Object.entries(data.headers)
        .map(([k, v]) => `<tr><td>${escapeHtml(k)}</td><td>${escapeHtml(v)}</td></tr>`)
        .join('');
}

function switchTab(name) {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    document.querySelector(`.tab[data-tab="${name}"]`).classList.add('active');
    document.getElementById('tab-' + name).classList.add('active');
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;','\'':'&#39;'}[c]));
}
