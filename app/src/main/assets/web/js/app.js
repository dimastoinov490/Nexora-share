// Nexora Share - Web App Client Logic
(function () {
  'use strict';

  // State
  const state = {
    currentTab: 'dashboard',
    photos: [],
    currentPhotoIndex: -1,
    videos: [],
    currentFolderPath: '',
    files: [],
    sortOrder: 'date_desc',
    searchQuery: '',
    searchResults: []
  };

  // DOM Elements
  const elements = {
    tabs: document.querySelectorAll('.nav-item'),
    views: document.querySelectorAll('.tab-view'),
    sidebar: document.getElementById('sidebar'),
    menuToggle: document.getElementById('menu-toggle'),
    globalSearch: document.getElementById('global-search'),
    clearSearch: document.getElementById('clear-search'),
    refreshBtn: document.getElementById('refresh-btn'),
    deviceModel: document.getElementById('device-model'),
    statPhotos: document.getElementById('stat-photos'),
    statVideos: document.getElementById('stat-videos'),
    statFiles: document.getElementById('stat-files'),
    statStorage: document.getElementById('stat-storage'),
    badgePhotos: document.getElementById('badge-photos'),
    badgeVideos: document.getElementById('badge-videos'),
    dashboardPhotos: document.getElementById('dashboard-recent-photos'),
    dashboardVideos: document.getElementById('dashboard-recent-videos'),
    photosGrid: document.getElementById('photos-grid'),
    photosPills: document.getElementById('photos-folder-pills'),
    videosGrid: document.getElementById('videos-grid'),
    videosPills: document.getElementById('videos-folder-pills'),
    breadcrumbs: document.getElementById('breadcrumbs'),
    filesSort: document.getElementById('files-sort'),
    folderShortcuts: document.getElementById('folder-shortcuts'),
    filesTbody: document.getElementById('files-tbody'),
    tableEmpty: document.getElementById('table-empty'),
    searchQueryText: document.getElementById('search-query-text'),
    searchCountText: document.getElementById('search-count-text'),
    searchResultsGrid: document.getElementById('search-results-grid'),
    btnCloseSearch: document.getElementById('btn-close-search'),
    // Photo Modal
    photoModal: document.getElementById('photo-modal'),
    modalPhotoName: document.getElementById('modal-photo-name'),
    modalPhotoMeta: document.getElementById('modal-photo-meta'),
    modalPhotoImg: document.getElementById('modal-photo-img'),
    modalPhotoDownload: document.getElementById('modal-photo-download'),
    modalPhotoClose: document.getElementById('modal-photo-close'),
    photoPrev: document.getElementById('photo-prev'),
    photoNext: document.getElementById('photo-next'),
    // Video Modal
    videoModal: document.getElementById('video-modal'),
    modalVideoName: document.getElementById('modal-video-name'),
    modalVideoMeta: document.getElementById('modal-video-meta'),
    modalVideoElement: document.getElementById('modal-video-element'),
    modalVideoDownload: document.getElementById('modal-video-download'),
    modalVideoClose: document.getElementById('modal-video-close'),
    toast: document.getElementById('toast')
  };

  // Utilities
  function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i];
  }

  function formatDate(timestamp) {
    if (!timestamp) return '';
    const d = new Date(timestamp);
    return d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  }

  function formatDuration(sec) {
    if (!sec || sec <= 0) return '00:00';
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
  }

  function showToast(msg) {
    elements.toast.textContent = msg;
    elements.toast.classList.add('show');
    setTimeout(() => elements.toast.classList.remove('show'), 3000);
  }

  // API Client
  async function fetchJson(endpoint) {
    try {
      const res = await fetch(endpoint);
      if (!res.ok) throw new Error(`HTTP error ${res.status}`);
      return await res.json();
    } catch (err) {
      console.error('API Error:', endpoint, err);
      return null;
    }
  }

  // Navigation
  function switchTab(tabName) {
    state.currentTab = tabName;
    elements.tabs.forEach(btn => {
      btn.classList.toggle('active', btn.dataset.tab === tabName);
    });
    elements.views.forEach(v => {
      v.classList.toggle('active', v.id === `view-${tabName}`);
    });
    if (elements.sidebar.classList.contains('open')) {
      elements.sidebar.classList.remove('open');
    }

    if (tabName === 'dashboard') loadDashboard();
    else if (tabName === 'photos') loadPhotos();
    else if (tabName === 'videos') loadVideos();
    else if (tabName === 'files') loadFiles(state.currentFolderPath);
  }

  // Status & Overview
  async function loadStatus() {
    const data = await fetchJson('/api/status');
    if (!data) return;

    if (data.deviceModel) elements.deviceModel.textContent = data.deviceModel;
    if (data.totalPhotos !== undefined) {
      elements.statPhotos.textContent = data.totalPhotos;
      elements.badgePhotos.textContent = data.totalPhotos;
    }
    if (data.totalVideos !== undefined) {
      elements.statVideos.textContent = data.totalVideos;
      elements.badgeVideos.textContent = data.totalVideos;
    }
    if (data.totalFiles !== undefined) elements.statFiles.textContent = data.totalFiles;
    if (data.totalSize !== undefined) elements.statStorage.textContent = formatBytes(data.totalSize);
  }

  // Dashboard Loader
  async function loadDashboard() {
    loadStatus();

    // Recent Photos Preview
    const pData = await fetchJson('/api/photos?limit=8');
    if (pData && pData.items) {
      renderPhotosGrid(pData.items.slice(0, 8), elements.dashboardPhotos);
    } else {
      elements.dashboardPhotos.innerHTML = '<div class="table-empty">Фотографии не найдены</div>';
    }

    // Recent Videos Preview
    const vData = await fetchJson('/api/videos?limit=6');
    if (vData && vData.items) {
      renderVideosGrid(vData.items.slice(0, 6), elements.dashboardVideos);
    } else {
      elements.dashboardVideos.innerHTML = '<div class="table-empty">Видеозаписи не найдены</div>';
    }
  }

  // Photos Gallery
  let currentPhotosFolder = 'all';

  async function loadPhotos(folder = currentPhotosFolder) {
    currentPhotosFolder = folder;
    elements.photosGrid.innerHTML = '<div class="loading-spinner"><div class="spin"></div> Загрузка фотографий...</div>';

    const url = folder === 'all' ? '/api/photos' : `/api/photos?folder=${encodeURIComponent(folder)}`;
    const data = await fetchJson(url);

    if (data && data.items && data.items.length > 0) {
      state.photos = data.items;
      renderPhotosGrid(state.photos, elements.photosGrid);
    } else {
      state.photos = [];
      elements.photosGrid.innerHTML = '<div class="table-empty" style="grid-column: 1 / -1;">В этой папке фотографий нет</div>';
    }
  }

  function renderPhotosGrid(items, container) {
    container.innerHTML = '';
    items.forEach((item, index) => {
      const card = document.createElement('div');
      card.className = 'photo-card';
      const thumbUrl = `/api/thumb?path=${encodeURIComponent(item.path)}&type=image`;

      card.innerHTML = `
        <div class="photo-thumb-wrap">
          <img src="${thumbUrl}" alt="${item.name}" loading="lazy" onerror="this.src='/api/stream?path=${encodeURIComponent(item.path)}'">
        </div>
        <div class="photo-meta">
          <div class="photo-title" title="${item.name}">${item.name}</div>
          <div class="photo-sub">
            <span>${formatBytes(item.size)}</span>
            <span>${formatDate(item.lastModified).split(',')[0]}</span>
          </div>
        </div>
      `;

      card.addEventListener('click', () => {
        openPhotoModal(item, items);
      });

      container.appendChild(card);
    });
  }

  // Lightbox
  function openPhotoModal(item, list) {
    const idx = list.findIndex(p => p.path === item.path);
    state.currentPhotoIndex = idx >= 0 ? idx : 0;
    state.modalPhotosList = list;
    showModalPhoto(list[state.currentPhotoIndex]);
    elements.photoModal.classList.add('active');
    document.body.style.overflow = 'hidden';
  }

  function showModalPhoto(item) {
    if (!item) return;
    elements.modalPhotoName.textContent = item.name;
    elements.modalPhotoMeta.textContent = `${formatBytes(item.size)} • ${formatDate(item.lastModified)}`;
    elements.modalPhotoImg.src = `/api/stream?path=${encodeURIComponent(item.path)}`;
    elements.modalPhotoDownload.href = `/api/download?path=${encodeURIComponent(item.path)}`;
  }

  function closePhotoModal() {
    elements.photoModal.classList.remove('active');
    elements.modalPhotoImg.src = '';
    document.body.style.overflow = '';
  }

  function stepPhoto(direction) {
    if (!state.modalPhotosList || state.modalPhotosList.length === 0) return;
    state.currentPhotoIndex = (state.currentPhotoIndex + direction + state.modalPhotosList.length) % state.modalPhotosList.length;
    showModalPhoto(state.modalPhotosList[state.currentPhotoIndex]);
  }

  // Videos Library
  let currentVideosFolder = 'all';

  async function loadVideos(folder = currentVideosFolder) {
    currentVideosFolder = folder;
    elements.videosGrid.innerHTML = '<div class="loading-spinner"><div class="spin"></div> Загрузка видеозаписей...</div>';

    const url = folder === 'all' ? '/api/videos' : `/api/videos?folder=${encodeURIComponent(folder)}`;
    const data = await fetchJson(url);

    if (data && data.items && data.items.length > 0) {
      state.videos = data.items;
      renderVideosGrid(state.videos, elements.videosGrid);
    } else {
      state.videos = [];
      elements.videosGrid.innerHTML = '<div class="table-empty" style="grid-column: 1 / -1;">В этой папке видеозаписей нет</div>';
    }
  }

  function renderVideosGrid(items, container) {
    container.innerHTML = '';
    items.forEach(item => {
      const card = document.createElement('div');
      card.className = 'video-card';
      const thumbUrl = `/api/thumb?path=${encodeURIComponent(item.path)}&type=video`;

      card.innerHTML = `
        <div class="video-thumb-wrap">
          <img src="${thumbUrl}" alt="${item.name}" loading="lazy">
          <div class="video-play-badge">
            <svg viewBox="0 0 24 24"><polygon points="5 3 19 12 5 21 5 3"/></svg>
          </div>
          ${item.duration > 0 ? `<div class="video-duration">${formatDuration(item.duration)}</div>` : ''}
        </div>
        <div class="video-meta">
          <div class="video-title" title="${item.name}">${item.name}</div>
          <div class="video-sub">
            <span>${formatBytes(item.size)}</span>
            <span>${formatDate(item.lastModified).split(',')[0]}</span>
          </div>
        </div>
      `;

      card.addEventListener('click', () => {
        openVideoModal(item);
      });

      container.appendChild(card);
    });
  }

  function openVideoModal(item) {
    elements.modalVideoName.textContent = item.name;
    elements.modalVideoMeta.textContent = `${formatBytes(item.size)} • ${formatDate(item.lastModified)}`;
    elements.modalVideoDownload.href = `/api/download?path=${encodeURIComponent(item.path)}`;

    // Set streaming URL (supports HTTP 206 Partial Content range requests)
    const streamUrl = `/api/stream?path=${encodeURIComponent(item.path)}`;
    elements.modalVideoElement.src = streamUrl;
    elements.modalVideoElement.load();
    elements.videoModal.classList.add('active');
    document.body.style.overflow = 'hidden';
    elements.modalVideoElement.play().catch(() => {});
  }

  function closeVideoModal() {
    elements.videoModal.classList.remove('active');
    elements.modalVideoElement.pause();
    elements.modalVideoElement.src = '';
    document.body.style.overflow = '';
  }

  // File Explorer
  async function loadFiles(relativePath = '') {
    state.currentFolderPath = relativePath;
    renderBreadcrumbs(relativePath);

    elements.filesTbody.innerHTML = '<tr><td colspan="4"><div class="loading-spinner"><div class="spin"></div> Загрузка списка файлов...</div></td></tr>';
    elements.tableEmpty.style.display = 'none';

    const sort = elements.filesSort.value;
    const url = `/api/files?path=${encodeURIComponent(relativePath)}&sort=${sort}`;
    const data = await fetchJson(url);

    if (data && data.items && data.items.length > 0) {
      state.files = data.items;
      renderFilesTable(state.files);
    } else {
      state.files = [];
      elements.filesTbody.innerHTML = '';
      elements.tableEmpty.style.display = 'block';
    }
  }

  function renderBreadcrumbs(path) {
    elements.breadcrumbs.innerHTML = '';

    const rootBtn = document.createElement('button');
    rootBtn.className = 'crumb-btn' + (path === '' ? ' active' : '');
    rootBtn.textContent = 'Внутренняя память';
    rootBtn.addEventListener('click', () => loadFiles(''));
    elements.breadcrumbs.appendChild(rootBtn);

    if (!path) return;

    const parts = path.split('/').filter(p => p.length > 0);
    let accum = '';

    parts.forEach((part, idx) => {
      accum += (accum ? '/' : '') + part;
      const currentAccum = accum;

      const sep = document.createElement('span');
      sep.className = 'crumb-sep';
      sep.textContent = '/';
      elements.breadcrumbs.appendChild(sep);

      const btn = document.createElement('button');
      btn.className = 'crumb-btn' + (idx === parts.length - 1 ? ' active' : '');
      btn.textContent = part;
      btn.addEventListener('click', () => loadFiles(currentAccum));
      elements.breadcrumbs.appendChild(btn);
    });
  }

  function getFileIcon(item) {
    if (item.isDirectory) {
      return `<div class="file-type-icon icon-dir">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
      </div>`;
    }
    const ext = (item.extension || '').toLowerCase();
    if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'heic', 'bmp'].includes(ext)) {
      return `<div class="file-type-icon icon-image">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
      </div>`;
    }
    if (['mp4', 'mkv', 'webm', 'mov', 'avi', '3gp', 'ts'].includes(ext)) {
      return `<div class="file-type-icon icon-video-f">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2"/></svg>
      </div>`;
    }
    if (['mp3', 'wav', 'ogg', 'm4a', 'flac', 'aac'].includes(ext)) {
      return `<div class="file-type-icon icon-audio">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>
      </div>`;
    }
    if (['pdf', 'doc', 'docx', 'txt', 'rtf', 'xlsx', 'xls', 'ppt', 'pptx'].includes(ext)) {
      return `<div class="file-type-icon icon-doc">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/></svg>
      </div>`;
    }
    if (['zip', 'rar', '7z', 'tar', 'gz', 'apk'].includes(ext)) {
      return `<div class="file-type-icon icon-zip">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="21 8 21 21 3 21 3 8"/><rect x="1" y="3" width="22" height="5"/><line x1="10" y1="12" x2="14" y2="12"/></svg>
      </div>`;
    }
    return `<div class="file-type-icon icon-other">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg>
    </div>`;
  }

  function renderFilesTable(items) {
    elements.filesTbody.innerHTML = '';
    items.forEach(item => {
      const tr = document.createElement('tr');

      const actionBtn = item.isDirectory
        ? `<button class="btn-icon" title="Открыть папку"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg></button>`
        : `<a class="btn-icon" href="/api/download?path=${encodeURIComponent(item.path)}" title="Скачать файл" download>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          </a>`;

      tr.innerHTML = `
        <td>
          <div class="file-name-cell">
            ${getFileIcon(item)}
            <span class="file-name-text" title="${item.name}">${item.name}</span>
          </div>
        </td>
        <td>${item.isDirectory ? '—' : formatBytes(item.size)}</td>
        <td class="td-date">${formatDate(item.lastModified)}</td>
        <td>${actionBtn}</td>
      `;

      tr.addEventListener('click', (e) => {
        // If download button clicked, do not navigate
        if (e.target.closest('a')) return;

        if (item.isDirectory) {
          loadFiles(item.path);
        } else {
          const ext = (item.extension || '').toLowerCase();
          if (['jpg', 'jpeg', 'png', 'webp', 'gif', 'heic'].includes(ext)) {
            openPhotoModal(item, [item]);
          } else if (['mp4', 'webm', 'mkv', 'mov'].includes(ext)) {
            openVideoModal(item);
          } else {
            // Trigger download
            window.location.href = `/api/download?path=${encodeURIComponent(item.path)}`;
          }
        }
      });

      elements.filesTbody.appendChild(tr);
    });
  }

  // Global Search
  let searchTimer = null;

  async function handleSearch(query) {
    const q = query.trim();
    if (!q) {
      elements.clearSearch.style.display = 'none';
      if (state.currentTab === 'search') switchTab('dashboard');
      return;
    }

    elements.clearSearch.style.display = 'block';
    state.currentTab = 'search';
    elements.views.forEach(v => v.classList.toggle('active', v.id === 'view-search'));
    elements.tabs.forEach(btn => btn.classList.remove('active'));

    elements.searchQueryText.textContent = `«${q}»`;
    elements.searchResultsGrid.innerHTML = '<div class="loading-spinner"><div class="spin"></div> Поиск файлов...</div>';

    const data = await fetchJson(`/api/search?q=${encodeURIComponent(q)}`);
    if (data && data.items && data.items.length > 0) {
      elements.searchCountText.textContent = `Найдено файлов: ${data.items.length}`;
      renderSearchResults(data.items);
    } else {
      elements.searchCountText.textContent = 'Ничего не найдено';
      elements.searchResultsGrid.innerHTML = '<div class="table-empty">Файлы по вашему запросу не найдены</div>';
    }
  }

  function renderSearchResults(items) {
    elements.searchResultsGrid.innerHTML = '';
    const tableWrap = document.createElement('div');
    tableWrap.className = 'explorer-card';
    tableWrap.innerHTML = `
      <div class="file-table-wrap">
        <table class="file-table">
          <thead>
            <tr>
              <th>Название</th>
              <th>Размер</th>
              <th>Путь</th>
              <th>Действие</th>
            </tr>
          </thead>
          <tbody id="search-tbody"></tbody>
        </table>
      </div>
    `;
    elements.searchResultsGrid.appendChild(tableWrap);
    const tbody = tableWrap.querySelector('#search-tbody');

    items.forEach(item => {
      const tr = document.createElement('tr');
      const actionBtn = `<a class="btn-icon" href="/api/download?path=${encodeURIComponent(item.path)}" title="Скачать" download>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
      </a>`;

      tr.innerHTML = `
        <td>
          <div class="file-name-cell">
            ${getFileIcon(item)}
            <span>${item.name}</span>
          </div>
        </td>
        <td>${item.isDirectory ? '—' : formatBytes(item.size)}</td>
        <td style="color: var(--text-muted); font-size: 0.8rem;">${item.path}</td>
        <td>${actionBtn}</td>
      `;

      tr.addEventListener('click', (e) => {
        if (e.target.closest('a')) return;
        if (item.isDirectory) {
          switchTab('files');
          loadFiles(item.path);
        } else {
          const ext = (item.extension || '').toLowerCase();
          if (['jpg', 'jpeg', 'png', 'webp', 'gif'].includes(ext)) {
            openPhotoModal(item, [item]);
          } else if (['mp4', 'webm', 'mkv', 'mov'].includes(ext)) {
            openVideoModal(item);
          } else {
            window.location.href = `/api/download?path=${encodeURIComponent(item.path)}`;
          }
        }
      });
      tbody.appendChild(tr);
    });
  }

  // Event Listeners
  function initListeners() {
    // Navigation
    elements.tabs.forEach(btn => {
      btn.addEventListener('click', () => switchTab(btn.dataset.tab));
    });

    document.querySelectorAll('[data-target]').forEach(el => {
      el.addEventListener('click', () => switchTab(el.dataset.target));
    });

    // Mobile Menu
    elements.menuToggle.addEventListener('click', () => {
      elements.sidebar.classList.toggle('open');
    });

    // Refresh
    elements.refreshBtn.addEventListener('click', () => {
      showToast('Обновление списка файлов...');
      if (state.currentTab === 'dashboard') loadDashboard();
      else if (state.currentTab === 'photos') loadPhotos();
      else if (state.currentTab === 'videos') loadVideos();
      else if (state.currentTab === 'files') loadFiles(state.currentFolderPath);
    });

    // Search
    elements.globalSearch.addEventListener('input', (e) => {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(() => handleSearch(e.target.value), 300);
    });

    elements.clearSearch.addEventListener('click', () => {
      elements.globalSearch.value = '';
      handleSearch('');
    });

    elements.btnCloseSearch.addEventListener('click', () => {
      elements.globalSearch.value = '';
      handleSearch('');
    });

    // Photos Pills
    elements.photosPills.querySelectorAll('.pill').forEach(btn => {
      btn.addEventListener('click', () => {
        elements.photosPills.querySelectorAll('.pill').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        loadPhotos(btn.dataset.folder);
      });
    });

    // Videos Pills
    elements.videosPills.querySelectorAll('.pill').forEach(btn => {
      btn.addEventListener('click', () => {
        elements.videosPills.querySelectorAll('.pill').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        loadVideos(btn.dataset.folder);
      });
    });

    // Files Sort
    elements.filesSort.addEventListener('change', () => {
      loadFiles(state.currentFolderPath);
    });

    // Folder shortcuts
    elements.folderShortcuts.querySelectorAll('.shortcut-chip').forEach(chip => {
      chip.addEventListener('click', () => {
        loadFiles(chip.dataset.path);
      });
    });

    // Lightbox Controls
    elements.modalPhotoClose.addEventListener('click', closePhotoModal);
    elements.photoModal.querySelector('.modal-backdrop').addEventListener('click', closePhotoModal);
    elements.photoPrev.addEventListener('click', () => stepPhoto(-1));
    elements.photoNext.addEventListener('click', () => stepPhoto(1));

    // Video Modal Controls
    elements.modalVideoClose.addEventListener('click', closeVideoModal);
    elements.videoModal.querySelector('.modal-backdrop').addEventListener('click', closeVideoModal);

    // Keyboard Navigation
    window.addEventListener('keydown', (e) => {
      if (elements.photoModal.classList.contains('active')) {
        if (e.key === 'Escape') closePhotoModal();
        else if (e.key === 'ArrowLeft') stepPhoto(-1);
        else if (e.key === 'ArrowRight') stepPhoto(1);
      } else if (elements.videoModal.classList.contains('active')) {
        if (e.key === 'Escape') closeVideoModal();
      }
    });
  }

  // Initialization
  document.addEventListener('DOMContentLoaded', () => {
    initListeners();
    loadDashboard();
  });
})();
