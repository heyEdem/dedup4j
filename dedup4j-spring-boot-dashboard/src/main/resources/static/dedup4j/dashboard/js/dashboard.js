(() => {
  const root = document.documentElement;
  const stored = localStorage.getItem('dedup4j-theme');
  if (stored) root.dataset.theme = stored;
  document.getElementById('theme-toggle')?.addEventListener('click', () => {
    const next = root.dataset.theme === 'dark' ? 'light' : 'dark';
    root.dataset.theme = next; localStorage.setItem('dedup4j-theme', next);
  });
  const base = window.location.pathname.endsWith('/') ? '.' : window.location.pathname;
  const get = path => fetch(`${base}${path}`).then(r => { if (!r.ok) throw new Error(`Request failed (${r.status})`); return r.json(); });
  const format = n => new Intl.NumberFormat('en', { notation: n > 999999 ? 'compact' : 'standard', maximumFractionDigits: 1 }).format(n || 0);
  const bytes = n => { if (!n) return '0 B'; const units=['B','KB','MB','GB','TB']; const i=Math.min(Math.floor(Math.log(n)/Math.log(1024)),4); return `${(n/Math.pow(1024,i)).toFixed(i?1:0)} ${units[i]}`; };
  const set = (id, value) => { const el=document.getElementById(id); if(el) el.textContent=value; };
  const escapeHtml = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  function renderOverview(d){set('avoided-bytes',bytes(d.avoidedBytes));set('total-uploads',format(d.uploads));set('new-uploads',format(d.newUploads));set('duplicates',format(d.duplicates));set('content-count',format(d.contentCount));set('logical-bytes',bytes(d.logicalBytes));set('physical-bytes',bytes(d.physicalBytes));}
  function renderInstances(items){set('instance-count',`${items.length} current`);const el=document.getElementById('instances-list');el.innerHTML=items.length?items.map(i=>`<div class="instance-row"><div><div class="instance-name">${escapeHtml(i.instanceName)}</div><div class="meta">${escapeHtml(i.instanceId)}</div></div><span class="badge">${escapeHtml(i.status)}</span></div>`).join(''):'<div class="empty-row">No instance data.</div>';}
  function renderFailures(items){const el=document.getElementById('failures-list');el.innerHTML=items.length?items.map(f=>`<div class="failure-row"><div><div class="failure-message">${escapeHtml(f.message)}</div><div class="meta">${escapeHtml(f.operation)} · ${new Date(f.occurredAt).toLocaleString()}</div></div><span class="badge">FAILED</span></div>`).join(''):'<div class="empty-row">No failures recorded.</div>';}
  Promise.all([get('/api/v1/overview'),get('/api/v1/instances/status'),get('/api/v1/failures')]).then(([o,i,f])=>{renderOverview(o);renderInstances(i);renderFailures(f);set('app-status',`Updated ${new Date().toLocaleTimeString()} · ${o.healthyInstanceCount}/${o.instanceCount} healthy`);}).catch(e=>{set('app-status',`Unable to load dashboard: ${e.message}`);document.getElementById('app-status').classList.add('error');});
})();
