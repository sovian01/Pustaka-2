// ============================================================================
// Pengganti seluruh render_template_string() Flask.
// Semua data lewat window.Android.*  (lihat WebAppInterface.kt) -- tanpa fetch(),
// tanpa endpoint HTTP, tanpa server. Router SPA sederhana berbasis location.hash.
// ============================================================================

const app = document.getElementById('app');

function h(strings, ...values) { // helper template literal biasa, cuma alias
  return strings.reduce((acc, s, i) => acc + s + (values[i] ?? ''), '');
}
function esc(s) {
  if (s === null || s === undefined) return '';
  return String(s).replace(/[&<>"']/g, c => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));
}
// Placeholder transparan -- menghindari <img src=""> yang di WebView bisa memicu
// permintaan ulang ke halaman saat ini (bug rendering yang tidak ada di browser desktop).
const BLANK_IMG = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg'%3E%3C/svg%3E";

// ---- Pembungkus pemilihan gambar cover native (menggantikan <input type=file> browser) ----
// Cover berukuran kecil, jadi tetap dikirim sebagai base64 seperti semula.
let fileRequestSeq = 0;
window.onNativeFilePicked = null;
function pickNativeImage() {
  return new Promise((resolve) => {
    const reqId = 'req' + (fileRequestSeq++);
    window.onNativeFilePicked = (id, base64, filename) => {
      if (id !== reqId) return;
      resolve(base64 ? { base64, filename } : null);
    };
    Android.pickImage(reqId);
  });
}

// ---- Pembungkus pemilihan Folder Chapter (SAF System Folder Picker) ----
// Chapter (pdf/txt/mp4/cbz) TIDAK PERNAH dibaca penuh ke memori/di-base64-kan di sini --
// hanya referensi foldernya yang diminta, itu sebabnya upload chapter besar tidak lagi
// membuat penyimpanan app membengkak atau membuat layar hitam.
let folderRequestSeq = 0;
window.onNativeFolderPicked = null;
function pickChapterFolder() {
  return new Promise((resolve) => {
    const reqId = 'freq' + (folderRequestSeq++);
    window.onNativeFolderPicked = (id, ok, path) => {
      if (id !== reqId) return;
      resolve(ok ? { path } : null);
    };
    Android.pickChapterSourceFolder(reqId);
  });
}

// ---- Router ----
window.addEventListener('hashchange', route);
window.addEventListener('DOMContentLoaded', route);
function nav(hash) { location.hash = hash; }

function route() {
  window.onAppResumed = null; // hanya relevan di halaman tambah/edit; bersihkan saat pindah halaman lain
  const hashRaw = location.hash.slice(1) || '/';
  // PENTING: pisahkan query string (?page=..) SEBELUM pecah jadi segmen path.
  // Tanpa ini, link penanda halaman (yang menambahkan ?page=N) akan membuat
  // "?page=N" ikut nempel di nama file saat dibuka lewat rute 'read', sehingga
  // Android.getChapterInfo() gagal menemukan file & selalu muncul "Tidak Ditemukan"
  // meski file & penandanya valid.
  const hash = hashRaw.split('?')[0] || '/';
  const parts = hash.split('/').filter(Boolean);
  // "Toggle Layar Penuh" di reader menyembunyikan status bar/navigation bar Android
  // (immersive mode) -- itu berlaku di tingkat window Activity, bukan cuma konten
  // WebView, jadi harus dimatikan manual saat pindah ke halaman NON-reader supaya
  // tidak "terbawa" ke daftar/detail/dst.
  if (parts[0] !== 'read' && typeof Android.exitFullscreen === 'function') Android.exitFullscreen();
  try {
    if (hash === '/') return renderList();
    if (parts[0] === 'playlists') return renderPlaylists();
    if (parts[0] === 'detail') return renderDetail(parts[1], parseQuery());
    if (parts[0] === 'add') return renderAddEdit(null);
    if (parts[0] === 'edit') return renderAddEdit(parts[1]);
    if (parts[0] === 'edit_chapters') return renderEditChapters(parts[1]);
    if (parts[0] === 'bookmarks') return renderBookmarks(parts[1]);
    if (parts[0] === 'read') return renderReader(decodeURIComponent(parts.slice(1).join('/')));
    renderList();
  } catch (e) {
    app.innerHTML = `<p style="color:#ff4444">Error: ${esc(e.message)}</p><a class="btn-back" href="#/">← Beranda</a>`;
  }
}
function parseQuery() {
  const q = location.hash.split('?')[1];
  const params = {};
  if (q) new URLSearchParams(q).forEach((v, k) => params[k] = v);
  return params;
}

// ============================== LIST (setara /) ==============================
function renderList() {
  const q = parseQuery();
  const sJudul = q.s_judul || '', sTipe = q.s_tipe || '', sGenre = q.s_genre || '',
        sStatus = q.s_status || '', cat = q.cat || '', fav = q.fav || '0';

  const resJson = Android.listCollections(sJudul, sTipe, sGenre, sStatus, cat, fav);
  const res = JSON.parse(resJson);

  const tabs = `
    <div style="display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid #333; padding-bottom:10px; margin-bottom:15px;">
      <div style="font-size:14px;">
        <a href="#/" style="color:${!cat && fav!=='1' ? '#ffca28':'#888'}; text-decoration:none;">Semua</a>
        <span style="color:#444; margin:0 5px;">|</span>
        <a href="#/?fav=1" style="color:${fav==='1' ? '#ffca28':'#888'}; text-decoration:none;">⭐ Favorit</a>
        ${res.active_playlist_name ? `<span style="color:#444; margin:0 5px;">|</span><span style="color:#ffca28; font-weight:bold;">📁 ${esc(res.active_playlist_name)}</span>` : ''}
      </div>
      <a href="#/playlists" style="font-size:20px; text-decoration:none; background:#333; padding:5px 12px; border-radius:8px; border:1px solid #555;">📁</a>
    </div>`;

  const searchBox = `
    <div class="search-box">
      <h3>--- PENCARIAN SPESIFIK ---</h3>
      <form id="filterForm" class="search-grid">
        <div><label>Judul:</label><input type="text" name="s_judul" value="${esc(sJudul)}"></div>
        <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px;">
          <div><label>Tipe:</label><input type="text" name="s_tipe" value="${esc(sTipe)}" placeholder="Manhwa, Anime"></div>
          <div><label>Status:</label><input type="text" name="s_status" value="${esc(sStatus)}" placeholder="Ongoing/Complete"></div>
        </div>
        <div><label>Genre:</label><input type="text" name="s_genre" value="${esc(sGenre)}" placeholder="Action, Fantasy..."></div>
        <button type="submit" style="background:#ffca28; color:black; font-weight:bold; margin-top:8px;">FILTER DATA</button>
      </form>
    </div>`;

  const cards = res.items.length ? res.items.map(item => `
    <a class="manga-card" href="#/detail/${item.id}">
      <img src="${item.image_path ? Android.getCoverUrl(item.image_path) : BLANK_IMG}" onerror="this.style.visibility='hidden'">
      <div>
        <div style="font-weight:bold;">${item.is_favorite ? '⭐ ' : ''}${esc(item.title)}</div>
        <div style="font-size:12px; color:#888; margin-top:4px;">
          <span class="badge">${esc(item.type||'-')}</span>
          <span class="badge">${esc(item.status||'-')}</span>
        </div>
        <div style="font-size:11px; color:#666; margin-top:4px;">${esc(item.chapters || item.episodes || '-')}</div>
      </div>
    </a>`).join('') : `<p style="text-align:center; color:#666; margin-top:40px;">Tidak ada data.</p>`;

  app.innerHTML = `<h1>📚 Pustaka</h1>${tabs}${searchBox}<div>${cards}</div><a class="fab" href="#/add">+</a>`;

  document.getElementById('filterForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    const params = new URLSearchParams();
    for (const [k, v] of f.entries()) if (v) params.set(k, v);
    if (cat) params.set('cat', cat);
    if (fav !== '0') params.set('fav', fav);
    nav('/?' + params.toString());
  });
}

// ============================== PLAYLISTS (setara /playlists) ==============================
function renderPlaylists() {
  const cats = JSON.parse(Android.listCategories());
  app.innerHTML = `
    <a href="#/" class="btn-back">← Kembali</a>
    <h2>📁 Daftar Playlist</h2>
    <form id="newCatForm" style="margin-bottom:20px; display:flex; gap:8px;">
      <input type="text" name="name" placeholder="Nama Playlist Baru..." required>
      <button type="submit" style="background:#ffca28; color:black; width:auto; white-space:nowrap;">+ Buat</button>
    </form>
    <div id="catList"></div>`;

  const listEl = document.getElementById('catList');
  listEl.innerHTML = cats.map(cat => `
    <div style="display:flex; justify-content:space-between; align-items:center; background:#1e1e1e; padding:12px; margin-bottom:8px; border-radius:8px; border:1px solid #333;">
      <a href="#/?cat=${cat.id}" style="text-decoration:none; color:white; font-weight:bold;">📂 ${esc(cat.name)}</a>
      <button data-id="${cat.id}" class="delCat" style="width:auto; color:#ff4444; background:none; border:none;">Hapus</button>
    </div>`).join('') || `<p style="color:#666;">Belum ada playlist.</p>`;

  listEl.querySelectorAll('.delCat').forEach(btn => btn.addEventListener('click', () => {
    if (confirm('Hapus playlist ini?')) { Android.deleteCategory(parseInt(btn.dataset.id)); renderPlaylists(); }
  }));

  document.getElementById('newCatForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const name = new FormData(e.target).get('name');
    if (name) { Android.addCategory(name); renderPlaylists(); }
  });
}

// ============================== DETAIL (setara /detail/<id>) ==============================
function renderDetail(id, q) {
  const data = JSON.parse(Android.getDetail(parseInt(id)));
  if (data.error) return nav('/');
  const item = data.item;
  const selectedCats = new Set(data.selected_category_ids);

  const chapterRows = data.chapters.length ? data.chapters.map(ch => `
    <div class="chapter-item">
      <a href="#/read/${encodeURIComponent(ch.file_name)}" class="chapter-info" style="flex-grow:1;">
        <span>● ${esc(ch.chapter_name)}</span>
        <small>${esc(ch.upload_date||'')}</small>
      </a>
    </div>`).join('') : `<p style="padding:20px; text-align:center; color:#666; font-size:12px; margin:0;">Belum ada file bacaan/tontonan yang diunggah.</p>`;

  app.innerHTML = `
    <a href="#/" class="btn-back">← Beranda</a>
    <div style="text-align:center; margin-top:10px;">
      <img src="${item.image_path ? Android.getCoverUrl(item.image_path) : BLANK_IMG}" onerror="this.style.visibility='hidden'" style="width:180px; border-radius:10px; border:2px solid #333;">
      <h2 style="margin:15px 0;">${esc(item.title)}</h2>
    </div>
    <div class="description-box"><strong>Sinopsis:</strong><br>${esc(item.description || 'Tidak ada deskripsi.')}</div>
    <ul class="info-list">
      <li><b>Genre:</b> ${esc(item.genre||'-')}</li>
      <li><b>Progress:</b> ${esc(item.chapters || item.episodes || '-')}</li>
      <li><b>Status:</b> ${esc(item.status||'-')}</li>
      <li><b>Tipe:</b> ${esc(item.type)}</li>
    </ul>
    <div style="display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid #333; padding-bottom:10px; margin-top:30px;">
      <h3 style="margin:0; font-size:16px;">Daftar Isi</h3>
      <div style="display:flex; gap:5px;">
        <a href="#/bookmarks/${item.id}" style="text-decoration:none; background:#333; color:white; padding:6px 12px; border-radius:20px; font-size:12px; font-weight:bold; border:1px solid #555;">🔖 PENANDA</a>
        <a href="#/edit_chapters/${item.id}" style="text-decoration:none; background:#333; color:white; padding:6px 12px; border-radius:20px; font-size:12px; font-weight:bold; border:1px solid #555;">✏️ EDIT</a>
        <button id="btnUpload" style="width:auto; text-decoration:none; background:#ffca28; color:black; padding:6px 12px; border-radius:20px; font-size:12px; font-weight:bold; border:none;">+ TAMBAH</button>
      </div>
    </div>
    <div class="chapter-container">${chapterRows}</div>
    <div class="action-bar">
      <button id="btnPlaylist" class="btn-small-yellow" style="border:none;">🏷 PLAYLIST</button>
      <a href="#/edit/${item.id}" class="btn-small-outline">📝 EDIT DATA</a>
      <button id="btnDelete" class="btn-small-red" style="border:none;">🗑️ HAPUS</button>
    </div>
    <div id="modalHost"></div>`;

  document.getElementById('btnDelete').addEventListener('click', () => {
    if (confirm('Hapus?')) { Android.deleteCollection(item.id); nav('/'); }
  });
  document.getElementById('btnUpload').addEventListener('click', () => openUploadModal(item.id));
  document.getElementById('btnPlaylist').addEventListener('click', () => openPlaylistModal(item, data.all_categories, selectedCats));
}

// Chapter (pdf/txt/mp4/cbz) TIDAK diunggah/disalin ke storage app. Yang disimpan hanyalah
// referensi ke file yang sudah ada di Folder Chapter milik user sendiri -- di situlah app
// membacanya setiap kali dibutuhkan. Karena itu langkahnya: (1) pastikan Folder Chapter
// sudah dipilih, (2) jelajahi folder itu di dalam app untuk MEMILIH file yang sudah ada,
// (3) simpan nama chapter + path relatifnya saja.
async function openUploadModal(collId) {
  const info = JSON.parse(Android.getChapterFolderInfo());
  if (!info.configured) return openChooseFolderModal(collId);
  openFileBrowserModal(collId, info.path);
}

function openChooseFolderModal(collId) {
  const host = document.getElementById('modalHost');
  host.innerHTML = `
    <div class="modal"><div class="modal-content">
      <h3 style="color:#ffca28; margin-top:0;">Pilih Folder Chapter</h3>
      <p style="font-size:13px; color:#aaa; line-height:1.5;">
        Pilih SATU folder di penyimpanan HP kamu tempat semua file chapter
        (PDF/TXT/MP4/CBZ) sudah/akan kamu simpan sendiri. App hanya akan
        <b>membaca</b> file dari folder ini -- tidak ada file yang disalin
        ke dalam penyimpanan app, jadi ukuran data app tidak akan membengkak.
      </p>
      <div style="display:flex; gap:10px; margin-top:15px;">
        <button id="cfCancel" style="flex:1; background:#444;">Batal</button>
        <button id="cfPick" style="flex:1; background:#ffca28; color:black; font-weight:bold;">📁 Pilih Folder...</button>
      </div>
    </div></div>`;
  document.getElementById('cfCancel').onclick = () => host.innerHTML = '';
  document.getElementById('cfPick').onclick = async () => {
    const res = await pickChapterFolder();
    if (!res) { return; }
    openFileBrowserModal(collId, res.path);
  };
}

function openFileBrowserModal(collId, folderPath) {
  const host = document.getElementById('modalHost');
  let subPath = '';
  let picked = null; // { rel_path, name }

  host.innerHTML = `
    <div class="modal"><div class="modal-content">
      <h3 style="color:#ffca28; margin-top:0;">Upload File Baru</h3>
      <label style="font-size:12px; color:#888;">Nama Chapter/Episode:</label>
      <input id="chName" type="text" placeholder="Contoh: EPISODE 1" style="margin-bottom:15px;">

      <label style="font-size:12px; color:#888;">
        Folder Chapter: <span style="color:#666;">${esc(folderPath)}</span>
        <button id="chChangeFolder" style="width:auto; display:inline; padding:2px 8px; font-size:11px; margin-left:6px; background:#333;">Ganti</button>
      </label>
      <div id="crumbs" style="font-size:11px; color:#ffca28; margin:8px 0; word-break:break-all;"></div>
      <div class="chapter-container" id="fbList" style="max-height:220px;"></div>
      <div id="chPicked" style="font-size:12px; color:#888; margin:10px 0;">Belum ada file dipilih.</div>

      <div style="display:flex; gap:10px;">
        <button id="chCancel" style="flex:1; background:#444;">Batal</button>
        <button id="chSubmit" style="flex:1; background:#ffca28; color:black; font-weight:bold;">Upload</button>
      </div>
    </div></div>`;

  document.getElementById('chCancel').onclick = () => host.innerHTML = '';
  document.getElementById('chChangeFolder').onclick = async () => {
    const res = await pickChapterFolder();
    if (res) openFileBrowserModal(collId, res.path); // gambar ulang modal dgn folder baru
  };

  function drawCrumbs() {
    const parts = subPath ? subPath.split('/') : [];
    const crumbHtml = ['<a href="#" data-idx="-1" class="crumb" style="color:#ffca28;">🏠 Root</a>']
      .concat(parts.map((p, i) => `<span style="color:#555;"> / </span><a href="#" data-idx="${i}" class="crumb" style="color:#ffca28;">${esc(p)}</a>`))
      .join('');
    document.getElementById('crumbs').innerHTML = crumbHtml;
    document.querySelectorAll('.crumb').forEach(a => a.onclick = (e) => {
      e.preventDefault();
      const idx = parseInt(a.dataset.idx);
      subPath = idx < 0 ? '' : parts.slice(0, idx + 1).join('/');
      drawList();
    });
  }

  function drawList() {
    drawCrumbs();
    const entries = JSON.parse(Android.listChapterFiles(subPath));
    const listEl = document.getElementById('fbList');
    if (!entries.length) {
      listEl.innerHTML = `<p style="padding:15px; text-align:center; color:#666; font-size:12px; margin:0;">Folder ini kosong (atau tidak ada file PDF/TXT/MP4/CBZ).</p>`;
      return;
    }
    listEl.innerHTML = entries.map(en => `
      <div class="chapter-item fb-entry" data-relpath="${esc(en.rel_path)}" data-isdir="${en.is_dir}" style="cursor:pointer;">
        <span>${en.is_dir ? '📁' : '📄'} ${esc(en.name)}</span>
      </div>`).join('');
    listEl.querySelectorAll('.fb-entry').forEach(row => row.onclick = () => {
      if (row.dataset.isdir === 'true') { subPath = row.dataset.relpath; drawList(); return; }
      picked = { rel_path: row.dataset.relpath, name: row.dataset.relpath.split('/').pop() };
      document.getElementById('chPicked').textContent = '✔ ' + picked.name;
      listEl.querySelectorAll('.fb-entry').forEach(r => r.style.background = '');
      row.style.background = '#2a2a2a';
    });
  }
  drawList();

  document.getElementById('chSubmit').onclick = () => {
    const chName = document.getElementById('chName').value.trim();
    if (!chName || !picked) { alert('Isi nama & pilih file terlebih dahulu.'); return; }
    const payload = { ch_name: chName, file_relpath: picked.rel_path };
    const res = JSON.parse(Android.addChapter(collId, JSON.stringify(payload)));
    if (res.error) { alert('Gagal menambahkan chapter: file tidak ditemukan/tidak didukung.'); return; }
    host.innerHTML = '';
    renderDetail(String(collId), {});
  };
}

function openPlaylistModal(item, allCategories, selectedCats) {
  const host = document.getElementById('modalHost');
  host.innerHTML = `
    <div class="modal"><div class="modal-content">
      <h3>Kelola Penyimpanan</h3>
      <label style="display:block; margin-bottom:15px; background:#2a2a2a; padding:10px; border-radius:5px;">
        <input type="checkbox" id="isFav" ${item.is_favorite ? 'checked' : ''} style="width:auto;"> ⭐ Favorit
      </label>
      <hr style="border-color:#333;">
      <div style="max-height:150px; overflow-y:auto; margin:15px 0;">
        ${allCategories.map(cat => `
          <label style="display:block; margin-bottom:8px;">
            <input type="checkbox" class="catCk" value="${cat.id}" ${selectedCats.has(cat.id) ? 'checked' : ''} style="width:auto;"> 📂 ${esc(cat.name)}
          </label>`).join('')}
      </div>
      <div style="display:flex; gap:10px;">
        <button id="plCancel" style="flex:1; background:#444;">Batal</button>
        <button id="plSave" style="flex:1; background:#ffca28; color:black;">Simpan</button>
      </div>
    </div></div>`;
  document.getElementById('plCancel').onclick = () => host.innerHTML = '';
  document.getElementById('plSave').onclick = () => {
    const isFav = document.getElementById('isFav').checked;
    const catIds = Array.from(document.querySelectorAll('.catCk:checked')).map(c => c.value).join(',');
    Android.updateItemCategories(item.id, isFav, catIds);
    host.innerHTML = '';
    renderDetail(String(item.id), {});
  };
}

// ============================== ADD / EDIT (setara /add, /edit/<id>) ==============================
function renderAddEdit(id) {
  const isEdit = !!id;
  const item = isEdit ? JSON.parse(Android.getDetail(parseInt(id))).item : null;

  app.innerHTML = `
    <a href="${isEdit ? '#/detail/'+id : '#/'}" class="btn-back">← Batal</a>
    <h2>${isEdit ? 'Edit Data' : 'Tambah Koleksi'}</h2>
    <div class="search-grid" style="gap:10px;">
      <div><label style="font-size:12px; color:#888;">Judul:</label><input id="fTitle" value="${item ? esc(item.title):''}" required></div>
      <div><label style="font-size:12px; color:#888;">Tipe:</label><input id="fType" value="${item ? esc(item.type):''}" placeholder="Manhwa, Anime, dll" required></div>
      <div><label style="font-size:12px; color:#888;">Genre:</label><input id="fGenre" value="${item ? esc(item.genre):''}" placeholder="Action, Romance..."></div>
      <div><label style="font-size:12px; color:#888;">Status:</label><input id="fStatus" value="${item ? esc(item.status):''}" placeholder="Ongoing/Complete"></div>
      <div><label style="font-size:12px; color:#888;">Chapter/Episode (Progress):</label><input id="fChapters" value="${item ? esc(item.chapters||item.episodes):''}"></div>
      <div><label style="font-size:12px; color:#888;">Sinopsis:</label><textarea id="fDesc" style="height:120px;">${item ? esc(item.description):''}</textarea></div>
      <div><label style="font-size:12px; color:#888;">Lokasi Simpan Cover:</label>
        <select id="fStorage"><option value="internal">Internal Storage</option><option value="eksternal">Eksternal Storage</option></select>
        <div id="fStorageWarn" style="font-size:11px; color:#ffca28; margin-top:6px; display:none;">
          ⚠️ Izin "Kelola semua file" belum diberikan -- cover akan tetap tersimpan di Internal Storage sampai izin diberikan.
          <button id="fStorageGrant" type="button" style="width:auto; padding:4px 10px; margin-top:6px; background:#ffca28; color:black; font-weight:bold;">Berikan Izin</button>
        </div>
      </div>
      <div>
        <label style="font-size:12px; color:#888;">Gambar Cover:</label>
        <button id="fPickImg">🖼️ ${isEdit ? 'Ganti Gambar (opsional)' : 'Pilih Gambar (opsional)'}</button>
        <div id="fImgPreview" style="font-size:12px; color:#888; margin-top:6px;"></div>
      </div>
      <button id="fSubmit" style="background:#ffca28; color:black; font-weight:bold; margin-top:10px;">${isEdit ? 'Update' : 'Simpan Koleksi'}</button>
    </div>`;

  let pickedImage = null;
  document.getElementById('fPickImg').addEventListener('click', async () => {
    const f = await pickNativeImage();
    if (f) { pickedImage = f; document.getElementById('fImgPreview').textContent = '✔ ' + f.filename; }
  });

  // "Eksternal Storage" butuh izin runtime Android ("Kelola semua file") yang tidak
  // dibutuhkan versi Flask (yang punya akses filesystem penuh) -- tanpa ini, memilih
  // Eksternal Storage akan diam-diam jatuh balik ke Internal Storage tanpa penjelasan.
  const fStorage = document.getElementById('fStorage');
  const fStorageWarn = document.getElementById('fStorageWarn');
  const fStorageGrant = document.getElementById('fStorageGrant');
  function refreshStorageWarn() {
    const hasPerm = typeof Android.hasManageStoragePermission === 'function' && Android.hasManageStoragePermission();
    fStorageWarn.style.display = (fStorage.value === 'eksternal' && !hasPerm) ? 'block' : 'none';
  }
  fStorage.addEventListener('change', refreshStorageWarn);
  fStorageGrant.addEventListener('click', () => Android.requestManageStoragePermission());
  refreshStorageWarn();
  // Izin diberikan lewat halaman Settings Android di luar app -- saat user kembali
  // (app di-resume), cek ulang statusnya supaya peringatan hilang otomatis.
  window.onAppResumed = refreshStorageWarn;

  document.getElementById('fSubmit').addEventListener('click', () => {
    const title = document.getElementById('fTitle').value.trim();
    const type = document.getElementById('fType').value.trim();
    if (!title || !type) { alert('Judul dan Tipe wajib diisi.'); return; }
    const payload = {
      title, type,
      genre: document.getElementById('fGenre').value,
      status: document.getElementById('fStatus').value,
      chapters: document.getElementById('fChapters').value,
      description: document.getElementById('fDesc').value,
      storage_location: document.getElementById('fStorage').value
    };
    if (pickedImage) { payload.image_base64 = pickedImage.base64; payload.image_file_name = pickedImage.filename; }

    if (isEdit) { Android.editCollection(parseInt(id), JSON.stringify(payload)); nav('/detail/' + id); }
    else {
      const res = JSON.parse(Android.addCollection(JSON.stringify(payload)));
      nav('/detail/' + res.id);
    }
  });
}

// ============================== EDIT CHAPTERS (reorder) ==============================
function renderEditChapters(id) {
  const data = JSON.parse(Android.getDetail(parseInt(id)));
  const item = data.item;
  app.innerHTML = `
    <a href="#/detail/${id}" class="btn-back">← Kembali</a>
    <h2>Edit Daftar Isi</h2>
    <p style="font-size:12px; color:#888;">Gunakan tombol panah untuk menggeser urutan, dan edit teks untuk mengubah nama.</p>
    <div id="chList"></div>
    <button id="saveOrder" style="background:#ffca28; color:black; font-weight:bold; margin-top:15px; padding:12px;">SIMPAN PERUBAHAN</button>`;

  const listEl = document.getElementById('chList');
  function draw() {
    listEl.innerHTML = data.chapters.map(ch => `
      <div class="chapter-edit-row" data-id="${ch.id}" style="display:flex; align-items:center; gap:12px; margin-bottom:10px; background:#1e1e1e; padding:12px; border-radius:8px; border:1px solid #333;">
        <div style="display:flex; flex-direction:column; gap:5px;">
          <button class="mvUp" style="padding:4px 10px; margin:0; background:#444; border:none; color:white; border-radius:4px; width:auto;">▲</button>
          <button class="mvDown" style="padding:4px 10px; margin:0; background:#444; border:none; color:white; border-radius:4px; width:auto;">▼</button>
        </div>
        <input type="text" class="chNameInput" value="${esc(ch.chapter_name)}" style="flex-grow:1; margin:0; font-weight:bold;">
        <button class="delCh" style="color:#ff4444; background:none; border:1px solid #ff4444; border-radius:4px; width:auto; padding:8px;">Hapus</button>
      </div>`).join('');
    listEl.querySelectorAll('.mvUp').forEach((btn, i) => btn.onclick = () => { if (i>0) { [data.chapters[i-1], data.chapters[i]] = [data.chapters[i], data.chapters[i-1]]; draw(); } });
    listEl.querySelectorAll('.mvDown').forEach((btn, i) => btn.onclick = () => { if (i<data.chapters.length-1) { [data.chapters[i+1], data.chapters[i]] = [data.chapters[i], data.chapters[i+1]]; draw(); } });
    listEl.querySelectorAll('.delCh').forEach((btn, i) => btn.onclick = () => {
      if (confirm('Hapus file ini?')) { Android.deleteChapter(data.chapters[i].id); data.chapters.splice(i,1); draw(); }
    });
  }
  draw();

  document.getElementById('saveOrder').addEventListener('click', () => {
    const rows = listEl.querySelectorAll('.chapter-edit-row');
    const payload = Array.from(rows).map((row, idx) => ({
      id: parseInt(row.dataset.id),
      name: row.querySelector('.chNameInput').value,
      order: idx + 1
    }));
    Android.editChapters(JSON.stringify(payload));
    nav('/detail/' + id);
  });
}

// ============================== BOOKMARKS ==============================
function renderBookmarks(collId) {
  const item = JSON.parse(Android.getDetail(parseInt(collId))).item;
  const bookmarks = JSON.parse(Android.listBookmarks(parseInt(collId)));
  app.innerHTML = `
    <a href="#/detail/${collId}" class="btn-back">← Kembali</a>
    <h2>🔖 Penanda Halaman</h2>
    <h3 style="color:#ffca28; margin-bottom:20px; border-bottom:1px solid #333; padding-bottom:10px;">${esc(item.title)}</h3>
    <div class="chapter-container" style="max-height:none;">
      ${bookmarks.length ? bookmarks.map(bm => `
        <div class="chapter-item">
          <a href="#/read/${encodeURIComponent(bm.file_name)}?page=${bm.page_index}" class="chapter-info" style="flex-grow:1;">
            <span>● ${esc(bm.chapter_name)}</span>
            <small style="color:#ffca28; font-weight:bold;">${bm.file_name.toLowerCase().endsWith('.txt') ? 'Posisi Scroll: '+bm.page_index : 'Halaman: '+(bm.page_index+1)}</small>
          </a>
          <button data-id="${bm.id}" class="delBm" style="background:#442222; border-radius:4px; padding:6px 12px; color:#ff4444; border:none; width:auto;">Hapus</button>
        </div>`).join('') : `<p style="padding:20px; text-align:center; color:#666; font-size:12px; margin:0;">Belum ada halaman yang ditandai.</p>`}
    </div>`;
  document.querySelectorAll('.delBm').forEach(btn => btn.onclick = () => {
    if (confirm('Hapus penanda ini?')) { Android.deleteBookmark(parseInt(btn.dataset.id)); renderBookmarks(collId); }
  });
}

// ============================== READER (setara /read/<filename>) ==============================
function renderReader(fileName) {
  const q = parseQuery();
  const pageParam = q.page !== undefined ? parseInt(q.page) : NaN;
  const info = JSON.parse(Android.getChapterInfo(fileName));
  const chMeta = JSON.parse(Android.getChapterIdByFileName(fileName));

  if (info.error) {
    app.innerHTML = `<div style="text-align:center; padding:60px 20px; color:#ffca28;">
      <h2>⚠️ File Tidak Ditemukan</h2><p>Gagal memuat file.<br>File ini mungkin telah dihapus secara manual lewat File Manager atau dipindahkan.</p><br>
      <a href="javascript:history.back()" class="btn-back">Kembali</a></div>`;
    return;
  }

  if (info.ext === 'pdf') return renderPagedReader(fileName, info.page_count, (i) => Android.getPdfPageUrl(fileName, i), chMeta, pageParam);
  if (info.ext === 'cbz') return renderPagedReader(fileName, info.page_count, (i) => Android.getCbzPageUrl(fileName, i), chMeta, pageParam);
  if (info.ext === 'txt') return renderNovelReader(info.text, chMeta, pageParam);
  if (info.ext === 'mp4') return renderVideoReader(fileName);

  // Setara fallback Python (`redirect(url_for('view_file', filename=filename))` untuk
  // ekstensi tak dikenal): tidak ada browser bawaan di app native, jadi serahkan ke
  // aplikasi lain di perangkat lewat "Buka dengan...".
  Android.openWithExternalApp(fileName);
  nav('/');
}

function bookmarkPage(chMeta, pageIndex, doneMsg) {
  if (!chMeta.chapter_id) { alert('Gagal menambahkan penanda halaman.'); return; }
  Android.addBookmark(chMeta.chapter_id, chMeta.collection_id, pageIndex);
  alert(doneMsg || ('Halaman ' + (pageIndex + 1) + ' berhasil ditandai!'));
}

// ---- Reader per-halaman (CBZ & PDF) — identik UI/fitur dgn versi Flask ----
function renderPagedReader(fileName, pageCount, pageUrlFn, chMeta, pageParam) {
  app.innerHTML = `
    <div class="reader-nav" id="reader-nav">
      <a href="javascript:history.back()" class="btn-back">← Kembali</a>
      <div style="display:flex; align-items:center;">
        <button id="btnBookmark" style="background:none; border:none; color:#ffca28; font-size:20px; margin-right:15px; width:auto;" title="Tandai Halaman">🔖</button>
        <button id="btnSettings" style="background:none; border:none; color:#ffca28; font-size:20px; width:auto;" title="Pengaturan">⚙️</button>
      </div>
    </div>

    <div id="settings-modal">
      <h4 style="margin-top:0; color:#ffca28; border-bottom:1px solid #333; padding-bottom:5px; margin-bottom:15px;">Gaya Membaca</h4>
      <div class="settings-group">
        <label>A. IMAGE FIT TO SCREEN</label>
        <select id="fit-select">
          <option value="off">4. Off (Default)</option>
          <option value="height">1. Fit Height</option>
          <option value="width">2. Fit Width</option>
          <option value="full">3. Full Screen</option>
        </select>
      </div>
      <div class="settings-group">
        <label>B. PAGE SCROLLING</label>
        <select id="scroll-select">
          <option value="vertical">0. Vertical (Default)</option>
          <option value="right-page">1. Right (Berpindah 1 Gambar)</option>
          <option value="left-page">2. Left (Berpindah 1 Gambar)</option>
          <option value="right-scroll">3. Right Scroll (Terhubung)</option>
          <option value="left-scroll">4. Left Scroll (Terhubung)</option>
        </select>
      </div>
      <button id="btnFullscreen" style="margin-top:10px;">Toggle Layar Penuh</button>
    </div>

    <div class="comic-container" id="comic-container"></div>

    <button class="toggle-thumb-btn" id="btnToggleThumb">📑</button>
    <div class="thumb-nav hidden" id="thumb-nav">
      <div class="thumb-container" id="thumb-container"></div>
      <div class="range-container">
        <span id="page-indicator">1 / ${pageCount}</span>
        <input type="range" id="page-slider" min="0" max="${Math.max(0, pageCount - 1)}" value="0">
      </div>
    </div>`;

  const container = document.getElementById('comic-container');
  const thumbContainer = document.getElementById('thumb-container');
  let currentPage = 0;
  const imagesElems = [];

  // Buat elemen gambar per-halaman dgn placeholder + lazy load manual (persis versi Flask)
  for (let i = 0; i < pageCount; i++) {
    const img = document.createElement('img');
    img.dataset.src = pageUrlFn(i);
    img.src = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 800 1200' fill='%23111'%3E%3C/svg%3E";
    container.appendChild(img);
    imagesElems.push(img);

    const thumb = document.createElement('img');
    thumb.loading = 'lazy';
    thumb.src = pageUrlFn(i);
    thumb.className = 'thumb-item';
    thumb.id = 'thumb-' + i;
    thumb.onclick = () => goToPage(i);
    thumbContainer.appendChild(thumb);
  }

  const lazyLoadObserver = new IntersectionObserver((entries, observer) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        const img = entry.target;
        if (img.dataset.src) { img.src = img.dataset.src; delete img.dataset.src; observer.unobserve(img); }
      }
    });
  }, { rootMargin: '300% 0px' });
  imagesElems.forEach(img => lazyLoadObserver.observe(img));

  const pageObserver = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        const index = imagesElems.indexOf(entry.target);
        if (index !== -1 && index !== currentPage) { currentPage = index; updateIndicators(); }
      }
    });
  }, { root: null, threshold: 0.1 });
  imagesElems.forEach(img => pageObserver.observe(img));

  function updateIndicators() {
    document.getElementById('page-slider').value = currentPage;
    document.getElementById('page-indicator').innerText = (currentPage + 1) + ' / ' + pageCount;
    document.querySelectorAll('.thumb-item').forEach((el, idx) => {
      if (idx === currentPage) { el.classList.add('active'); el.scrollIntoView({ behavior:'smooth', inline:'center', block:'nearest' }); }
      else el.classList.remove('active');
    });
  }
  function goToPage(index) {
    index = Math.max(0, Math.min(pageCount - 1, parseInt(index)));
    const isLongJump = Math.abs(currentPage - index) > 3;
    currentPage = index;
    const target = imagesElems[index];
    if (target.dataset.src) { target.src = target.dataset.src; delete target.dataset.src; }
    target.scrollIntoView({ behavior: isLongJump ? 'auto' : 'smooth', inline:'center', block:'start' });
    updateIndicators();
  }

  document.getElementById('page-slider').addEventListener('input', (e) => goToPage(e.target.value));
  document.getElementById('btnToggleThumb').onclick = () => document.getElementById('thumb-nav').classList.toggle('hidden');
  document.getElementById('btnSettings').onclick = () => {
    const modal = document.getElementById('settings-modal');
    modal.style.display = modal.style.display === 'block' ? 'none' : 'block';
  };
  document.getElementById('fit-select').addEventListener('change', applyReaderSettings);
  document.getElementById('scroll-select').addEventListener('change', applyReaderSettings);
  document.getElementById('btnFullscreen').onclick = () => Android.toggleFullscreen();
  document.getElementById('btnBookmark').onclick = () => bookmarkPage(chMeta, currentPage);

  function applyReaderSettings() {
    const fit = document.getElementById('fit-select').value;
    const scroll = document.getElementById('scroll-select').value;
    container.className = 'comic-container';
    container.dir = 'ltr';
    if (fit === 'height') container.classList.add('fit-height');
    else if (fit === 'width') container.classList.add('fit-width');
    else if (fit === 'full') container.classList.add('fit-full');
    if (scroll !== 'vertical') {
      container.classList.add('scroll-h');
      if (scroll.includes('right')) container.dir = 'rtl';
      if (scroll.includes('page')) container.classList.add('scroll-paginated');
    }
  }

  // Lompat ke halaman dari penanda (kalau ada ?page=)
  if (!isNaN(pageParam) && pageParam >= 0 && pageParam < pageCount) {
    currentPage = pageParam;
    for (let i = Math.max(0, pageParam - 2); i <= Math.min(pageCount - 1, pageParam + 2); i++) {
      if (imagesElems[i].dataset.src) { imagesElems[i].src = imagesElems[i].dataset.src; delete imagesElems[i].dataset.src; }
    }
    setTimeout(() => { imagesElems[pageParam].scrollIntoView({ behavior:'auto', inline:'center', block:'start' }); updateIndicators(); }, 50);
  } else {
    updateIndicators();
  }
}

// ---- Reader Novel/TXT — identik styling dgn versi Flask ----
function renderNovelReader(text, chMeta, pageParam) {
  app.innerHTML = `
    <style>
      body { background-color:#1a1a1a !important; color:#d4d4d4 !important; }
      .novel-container { max-width:800px; margin:80px auto 50px auto; padding:0 20px; font-size:18px; line-height:1.8; white-space:pre-wrap; font-family:'Georgia', serif; }
    </style>
    <div class="reader-nav" style="background:rgba(20,20,20,0.95);">
      <a href="javascript:history.back()" class="btn-back">← Kembali</a>
      <button id="btnBookmark" style="background:none; border:none; color:#ffca28; font-size:20px; width:auto;" title="Tandai Posisi">🔖</button>
    </div>
    <div class="novel-container">${esc(text)}</div>`;

  document.getElementById('btnBookmark').onclick = () => bookmarkPage(chMeta, Math.round(window.scrollY), 'Posisi membaca berhasil ditandai!');

  if (!isNaN(pageParam)) {
    setTimeout(() => window.scrollTo({ top: pageParam, behavior: 'auto' }), 200);
  }
}

// ---- Reader Video/MP4 — pakai URL lokal ber-Range supaya bisa di-seek seperti browser ----
function renderVideoReader(fileName) {
  app.innerHTML = `
    <style>
      body { background:#000 !important; }
      .video-shell { display:flex; flex-direction:column; align-items:center; min-height:100vh; }
      .video-container { width:100%; max-width:1000px; margin-top:20px; padding:0 10px; box-sizing:border-box; }
      .video-container video { width:100%; border-radius:8px; outline:none; box-shadow:0 5px 20px rgba(255,202,40,0.1); }
    </style>
    <div class="video-shell">
      <div class="reader-nav" style="position:static; width:100%; background:#111;">
        <a href="javascript:history.back()" class="btn-back">← Kembali</a>
      </div>
      <div class="video-container">
        <video controls autoplay src="${Android.getChapterRawUrl(fileName)}">
          Browser Anda tidak mendukung pemutar video ini.
        </video>
      </div>
    </div>`;
}
