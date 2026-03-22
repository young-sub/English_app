const STORAGE_KEYS = {
  favorites: "tts-preset-lab-favorites-v1",
  seen: "tts-preset-lab-seen-v1",
  excluded: "tts-preset-lab-excluded-v1",
};

const state = {
  config: null,
  selectedModelId: null,
  provider: "auto",
  favorites: loadJson(STORAGE_KEYS.favorites, []),
  seenByModel: loadJson(STORAGE_KEYS.seen, {}),
  excludedByModel: loadJson(STORAGE_KEYS.excluded, {}),
  resultCount: 0,
  currentBatchCards: [],
  autoplayEnabled: true,
  autoRotatePromptEnabled: false,
};

const elements = {
  gpuName: document.getElementById("gpu-name"),
  defaultProvider: document.getElementById("default-provider"),
  cudaWheelStatus: document.getElementById("cuda-wheel-status"),
  modelGrid: document.getElementById("model-grid"),
  promptSelect: document.getElementById("prompt-select"),
  textInput: document.getElementById("text-input"),
  speedRange: document.getElementById("speed-range"),
  speedValue: document.getElementById("speed-value"),
  providerRow: document.getElementById("provider-row"),
  autoplayToggle: document.getElementById("autoplay-toggle"),
  autoRotatePromptToggle: document.getElementById("auto-rotate-prompt-toggle"),
  progressSummary: document.getElementById("progress-summary"),
  nextBatchPreview: document.getElementById("next-batch-preview"),
  generateBatchButton: document.getElementById("generate-batch-button"),
  replayBatchButton: document.getElementById("replay-batch-button"),
  resetSeenButton: document.getElementById("reset-seen-button"),
  playFavoritesButton: document.getElementById("play-favorites-button"),
  clearExcludedButton: document.getElementById("clear-excluded-button"),
  clearResultsButton: document.getElementById("clear-results-button"),
  favoritesGrid: document.getElementById("favorites-grid"),
  favoritesEmpty: document.getElementById("favorites-empty"),
  excludedGrid: document.getElementById("excluded-grid"),
  excludedEmpty: document.getElementById("excluded-empty"),
  statusLine: document.getElementById("status-line"),
  resultsGrid: document.getElementById("results-grid"),
  resultCardTemplate: document.getElementById("result-card-template"),
  favoriteCardTemplate: document.getElementById("favorite-card-template"),
  excludedCardTemplate: document.getElementById("excluded-card-template"),
};

init().catch((error) => {
  setStatus(`초기화 실패: ${error.message}`, true);
});

async function init() {
  const response = await fetch("/api/config");
  state.config = await response.json();
  state.provider = state.config.defaultProvider;
  state.selectedModelId = state.config.models[1]?.id || state.config.models[0].id;
  elements.gpuName.textContent = state.config.gpuName || "미감지";
  elements.defaultProvider.textContent = state.config.defaultProvider;
  elements.cudaWheelStatus.textContent = state.config.cudaWheelReady ? "GPU ready" : "CPU fallback";
  renderModelGrid();
  renderPromptSelect();
  renderProviderRow();
  bindEvents();
  renderBatchState();
  renderFavorites();
  renderExcluded();
  setStatus("준비 완료. 예시 문장을 고르고 새 10개 생성을 누르면 됩니다.");
}

function bindEvents() {
  elements.promptSelect.addEventListener("change", () => {
    const preset = state.config.textPresets.find((item) => item.id === elements.promptSelect.value);
    if (preset) {
      elements.textInput.value = preset.text;
    }
  });

  elements.speedRange.addEventListener("input", () => {
    elements.speedValue.textContent = `${Number(elements.speedRange.value).toFixed(2)}x`;
  });

  elements.autoplayToggle.addEventListener("change", () => {
    state.autoplayEnabled = elements.autoplayToggle.checked;
  });

  elements.autoRotatePromptToggle.addEventListener("change", () => {
    state.autoRotatePromptEnabled = elements.autoRotatePromptToggle.checked;
  });

  elements.generateBatchButton.addEventListener("click", async () => {
    await generateNextBatch();
  });

  elements.replayBatchButton.addEventListener("click", async () => {
    await autoplayBatch(state.currentBatchCards);
  });

  elements.resetSeenButton.addEventListener("click", () => {
    state.seenByModel[state.selectedModelId] = [];
    persistJson(STORAGE_KEYS.seen, state.seenByModel);
    renderBatchState();
    setStatus("생성 기록을 초기화했습니다. 같은 화자를 다시 포함할 수 있습니다.");
  });

  elements.clearExcludedButton.addEventListener("click", () => {
    state.excludedByModel[state.selectedModelId] = [];
    persistJson(STORAGE_KEYS.excluded, state.excludedByModel);
    renderBatchState();
    renderExcluded();
    renderFavorites();
    refreshExcludeButtons();
    setStatus("제외 목록을 비웠습니다.");
  });

  elements.clearResultsButton.addEventListener("click", () => {
    clearActiveCards();
    elements.resultsGrid.innerHTML = "";
    state.currentBatchCards = [];
    state.resultCount = 0;
    setStatus("최근 생성 결과를 비웠습니다.");
  });

  elements.playFavoritesButton.addEventListener("click", async () => {
    await autoplayFavorites();
  });
}

function renderModelGrid() {
  elements.modelGrid.innerHTML = "";
  for (const model of state.config.models) {
    const button = document.createElement("button");
    button.className = `model-card ${model.id === state.selectedModelId ? "active" : ""}`;
    button.innerHTML = `
      <span class="model-badge">${model.label}</span>
      <strong>${model.subtitle}</strong>
      <span class="model-detail">${model.totalSpeakers === 1 ? "고정 화자" : `${model.totalSpeakers} speakers`}</span>
    `;
    button.addEventListener("click", () => {
      state.selectedModelId = model.id;
      renderModelGrid();
      renderBatchState();
      renderFavorites();
      renderExcluded();
      setStatus(`${model.label} 기준으로 배치 탐색을 전환했습니다.`);
    });
    elements.modelGrid.appendChild(button);
  }
}

function renderPromptSelect() {
  elements.promptSelect.innerHTML = "";
  for (const preset of state.config.textPresets) {
    const option = document.createElement("option");
    option.value = preset.id;
    option.textContent = preset.label;
    elements.promptSelect.appendChild(option);
  }
  const first = state.config.textPresets[0];
  elements.promptSelect.value = first.id;
  elements.textInput.value = first.text;
}

function renderProviderRow() {
  elements.providerRow.innerHTML = "";
  for (const provider of state.config.providers) {
    const button = document.createElement("button");
    button.className = `segmented-btn ${provider === state.provider ? "active" : ""}`;
    button.textContent = provider.toUpperCase();
    button.addEventListener("click", () => {
      state.provider = provider;
      renderProviderRow();
      setStatus(`provider=${provider}`);
    });
    elements.providerRow.appendChild(button);
  }
}

function renderBatchState() {
  const model = currentModel();
  const skipped = skippedForModel(model.id);
  const nextBatch = takeNextSpeakers(speakerDeckForModel(model), skipped, 10);
  elements.progressSummary.textContent = `${model.label} 기준 ${seenForModel(model.id).length}/${model.totalSpeakers}개 확인됨 · 제외 ${excludedForModel(model.id).length}개`;
  elements.nextBatchPreview.textContent = nextBatch.length
    ? nextBatch.map((speakerId) => `speaker ${speakerId}`).join(" · ")
    : "남은 화자가 없습니다.";
}

async function generateNextBatch() {
  const model = currentModel();
  const text = elements.textInput.value.trim();
  if (state.autoRotatePromptEnabled) {
    rotatePrompt();
  }
  const activeText = elements.textInput.value.trim();
  if (!activeText) {
    setStatus("텍스트를 입력해 주세요.", true);
    return;
  }

  const seen = seenForModel(model.id);
  const nextBatch = takeNextSpeakers(speakerDeckForModel(model), skippedForModel(model.id), 10);
  if (!nextBatch.length) {
    setStatus("더 이상 생성할 새 화자가 없습니다. 생성 기록 초기화를 눌러 다시 시작할 수 있습니다.", true);
    return;
  }

  elements.generateBatchButton.disabled = true;
  state.currentBatchCards = [];
  setStatus(`${model.label} 새 10개 배치를 생성합니다...`);

  for (const [index, speakerId] of nextBatch.entries()) {
    setStatus(`${index + 1}/${nextBatch.length} 생성 중 · speaker ${speakerId}`);
    const result = await synthesizeSpeaker(model, activeText, speakerId);
    if (!result) {
      elements.generateBatchButton.disabled = false;
      return;
    }
    state.currentBatchCards.push(appendResultCard(result));
    seen.push(speakerId);
  }

  state.seenByModel[model.id] = seen;
  persistJson(STORAGE_KEYS.seen, state.seenByModel);
  renderBatchState();
  elements.generateBatchButton.disabled = false;
  setStatus(`새 10개 생성 완료. 즐겨찾기로 저장하거나 그대로 연속 청취할 수 있습니다.`);
  await autoplayBatch(state.currentBatchCards);
}

async function synthesizeSpeaker(model, text, speakerId) {
  const response = await fetch("/api/synthesize", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      modelId: model.id,
      text,
      speakerId,
      provider: state.provider,
      speed: Number(elements.speedRange.value),
    }),
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => ({ error: "unknown" }));
    setStatus(`생성 실패: ${payload.error || response.statusText}`, true);
    return null;
  }
  const blob = await response.blob();
  const activeProvider = response.headers.get("X-Active-Provider") || state.provider;
  const sampleRate = response.headers.get("X-Sample-Rate") || "-";
  return {
    favoriteId: `${model.id}:${speakerId}`,
    modelId: model.id,
    speakerId,
    title: `${model.label} · speaker ${speakerId}`,
    meta: `${model.subtitle} · provider=${activeProvider} · ${sampleRate}Hz`,
    blob,
    text,
  };
}

function appendResultCard(result) {
  state.resultCount += 1;
  const fragment = elements.resultCardTemplate.content.cloneNode(true);
  const card = fragment.querySelector(".result-card");
  const title = fragment.querySelector(".result-title");
  const meta = fragment.querySelector(".result-meta");
  const audio = fragment.querySelector(".audio-player");
  const download = fragment.querySelector(".download-link");
  const favoriteButton = fragment.querySelector(".favorite-toggle");
  const excludeButton = fragment.querySelector(".exclude-toggle");

  const objectUrl = URL.createObjectURL(result.blob);
  card.dataset.favoriteId = result.favoriteId;
  card.dataset.modelId = result.modelId;
  card.dataset.speakerId = String(result.speakerId);
  title.textContent = `${String(state.resultCount).padStart(2, "0")}. ${result.title}`;
  meta.textContent = result.meta;
  audio.src = objectUrl;
  download.href = objectUrl;
  download.download = `${result.title.replace(/\s+/g, "_")}.wav`;
  favoriteButton.textContent = isFavorite(result.favoriteId) ? "저장됨" : "즐겨찾기 저장";
  if (isFavorite(result.favoriteId)) {
    favoriteButton.classList.add("saved");
  }
  favoriteButton.addEventListener("click", async () => {
    await toggleFavorite(result, favoriteButton, objectUrl);
  });
  excludeButton.textContent = isExcluded(result.modelId, result.speakerId) ? "제외됨" : "제외 목록 추가";
  if (isExcluded(result.modelId, result.speakerId)) {
    excludeButton.classList.add("saved");
  }
  excludeButton.addEventListener("click", () => {
    toggleExcluded(result.modelId, result.speakerId, result.title, result.meta);
  });

  elements.resultsGrid.prepend(fragment);
  return { ...result, audio, card, objectUrl };
}

async function toggleFavorite(result, button, objectUrl) {
  if (isFavorite(result.favoriteId)) {
    state.favorites = state.favorites.filter((item) => item.favoriteId !== result.favoriteId);
    persistJson(STORAGE_KEYS.favorites, state.favorites);
    button.textContent = "즐겨찾기 저장";
    button.classList.remove("saved");
    renderFavorites();
    return;
  }

  const audioDataUrl = await blobToDataUrl(result.blob);
  state.favorites.unshift({
    favoriteId: result.favoriteId,
    modelId: result.modelId,
    speakerId: result.speakerId,
    title: result.title,
    meta: result.meta,
    text: result.text,
    audioDataUrl,
    objectUrl,
    createdAt: Date.now(),
  });
  persistJson(STORAGE_KEYS.favorites, state.favorites);
  button.textContent = "저장됨";
  button.classList.add("saved");
  renderFavorites();
}

function renderFavorites() {
  elements.favoritesGrid.innerHTML = "";
  const favoritesForModel = state.favorites.filter((item) => item.modelId === currentModel().id);
  elements.favoritesEmpty.classList.toggle("hidden", favoritesForModel.length > 0);
  elements.playFavoritesButton.disabled = favoritesForModel.length === 0;

  for (const favorite of favoritesForModel) {
    const fragment = elements.favoriteCardTemplate.content.cloneNode(true);
    fragment.querySelector(".favorite-title").textContent = favorite.title;
    fragment.querySelector(".favorite-meta").textContent = favorite.meta;
    fragment.querySelector(".favorite-text").textContent = favorite.text;
    const audio = fragment.querySelector(".favorite-audio");
    audio.src = favorite.audioDataUrl;
    audio.dataset.favoriteId = favorite.favoriteId;
    const excludeButton = fragment.querySelector(".favorite-exclude");
    excludeButton.textContent = isExcluded(favorite.modelId, favorite.speakerId) ? "제외됨" : "제외 목록 추가";
    excludeButton.classList.toggle("saved", isExcluded(favorite.modelId, favorite.speakerId));
    excludeButton.addEventListener("click", () => {
      toggleExcluded(favorite.modelId, favorite.speakerId, favorite.title, favorite.meta);
    });
    fragment.querySelector(".favorite-remove").addEventListener("click", () => {
      state.favorites = state.favorites.filter((item) => item.favoriteId !== favorite.favoriteId);
      persistJson(STORAGE_KEYS.favorites, state.favorites);
      updateFavoriteButtons(favorite.favoriteId);
      renderFavorites();
    });
    elements.favoritesGrid.appendChild(fragment);
  }
}

function renderExcluded() {
  elements.excludedGrid.innerHTML = "";
  const excluded = excludedForModel(currentModel().id);
  elements.excludedEmpty.classList.toggle("hidden", excluded.length > 0);
  elements.clearExcludedButton.disabled = excluded.length === 0;

  for (const entry of excluded) {
    const fragment = elements.excludedCardTemplate.content.cloneNode(true);
    fragment.querySelector(".excluded-title").textContent = entry.title;
    fragment.querySelector(".excluded-meta").textContent = entry.meta;
    fragment.querySelector(".excluded-remove").addEventListener("click", () => {
      state.excludedByModel[currentModel().id] = excluded.filter((item) => item.speakerId !== entry.speakerId);
      persistJson(STORAGE_KEYS.excluded, state.excludedByModel);
      renderBatchState();
      renderExcluded();
      renderFavorites();
      refreshExcludeButtons();
      setStatus(`speaker ${entry.speakerId} 제외를 해제했습니다.`);
    });
    elements.excludedGrid.appendChild(fragment);
  }
}

async function autoplayFavorites() {
  const favoritesForModel = state.favorites.filter((item) => item.modelId === currentModel().id);
  if (!favoritesForModel.length) {
    setStatus("먼저 즐겨찾기를 하나 이상 저장해 주세요.", true);
    return;
  }

  for (let index = 0; index < favoritesForModel.length; index += 1) {
    const favorite = favoritesForModel[index];
    const audio = document.querySelector(`.favorite-audio[data-favorite-id="${favorite.favoriteId}"]`);
    const card = audio?.closest(".favorite-card");
    if (!audio || !card) {
      continue;
    }
    setStatus(`즐겨찾기 재생 ${index + 1}/${favoritesForModel.length} · speaker ${favorite.speakerId}`);
    highlightActiveCard(card);
    try {
      audio.currentTime = 0;
      await audio.play();
      await waitForAudioEnd(audio);
    } catch (error) {
      setStatus("즐겨찾기 연속 재생이 중단되었습니다. 브라우저 재생 권한을 확인해 주세요.", true);
      return;
    }
  }
  clearActiveCards();
  setStatus("즐겨찾기 연속 재생이 끝났습니다.");
}

function updateFavoriteButtons(favoriteId) {
  document.querySelectorAll(`.result-card[data-favorite-id="${favoriteId}"] .favorite-toggle`).forEach((button) => {
    button.textContent = isFavorite(favoriteId) ? "저장됨" : "즐겨찾기 저장";
    button.classList.toggle("saved", isFavorite(favoriteId));
  });
}

async function autoplayBatch(batchCards) {
  if (!state.autoplayEnabled || !batchCards.length) {
    return;
  }
  for (let index = 0; index < batchCards.length; index += 1) {
    const entry = batchCards[index];
    setStatus(`연속 재생 ${index + 1}/${batchCards.length} · speaker ${entry.speakerId}`);
    highlightActiveCard(entry.card);
    try {
      entry.audio.currentTime = 0;
      await entry.audio.play();
      await waitForAudioEnd(entry.audio);
    } catch (error) {
      setStatus(`자동 재생이 중단되었습니다. 브라우저에서 재생 허용 후 다시 시도해 주세요.`, true);
      return;
    }
  }
  clearActiveCards();
  setStatus("현재 배치 연속 재생이 끝났습니다.");
}

function highlightActiveCard(activeCard) {
  document.querySelectorAll(".result-card, .favorite-card").forEach((card) => {
    card.classList.toggle("playing", card === activeCard);
  });
}

function clearActiveCards() {
  document.querySelectorAll(".result-card, .favorite-card").forEach((card) => card.classList.remove("playing"));
}

function waitForAudioEnd(audio) {
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      audio.removeEventListener("ended", handleEnd);
      audio.removeEventListener("error", handleError);
    };
    const handleEnd = () => {
      cleanup();
      resolve();
    };
    const handleError = () => {
      cleanup();
      reject(new Error("audio playback failed"));
    };
    audio.addEventListener("ended", handleEnd, { once: true });
    audio.addEventListener("error", handleError, { once: true });
  });
}

function currentModel() {
  return state.config.models.find((model) => model.id === state.selectedModelId);
}

function speakerDeckForModel(model) {
  if (Array.isArray(model.speakerDeck) && model.speakerDeck.length) {
    return model.speakerDeck;
  }
  return buildFallbackSpeakerDeck(model.totalSpeakers || 1, 10);
}

function seenForModel(modelId) {
  return Array.isArray(state.seenByModel[modelId]) ? [...state.seenByModel[modelId]] : [];
}

function excludedForModel(modelId) {
  return Array.isArray(state.excludedByModel[modelId]) ? [...state.excludedByModel[modelId]] : [];
}

function skippedForModel(modelId) {
  const seenIds = seenForModel(modelId);
  const excludedIds = excludedForModel(modelId).map((item) => item.speakerId);
  return [...new Set([...seenIds, ...excludedIds])];
}

function buildFallbackSpeakerDeck(totalSpeakers, batchSize) {
  const total = Math.max(Number(totalSpeakers) || 1, 1);
  if (total <= 1) {
    return [0];
  }
  const bucketCount = Math.min(Math.max(batchSize, 1), total);
  const boundaries = [];
  for (let index = 0; index <= bucketCount; index += 1) {
    boundaries.push(Math.round((index * total) / bucketCount));
  }
  const buckets = [];
  for (let index = 0; index < bucketCount; index += 1) {
    const start = boundaries[index];
    const end = boundaries[index + 1];
    const bucket = [];
    for (let speakerId = start; speakerId < end; speakerId += 1) {
      bucket.push(speakerId);
    }
    if (bucket.length) {
      buckets.push(bucket);
    }
  }
  const deck = [];
  while (buckets.some((bucket) => bucket.length > 0)) {
    for (const bucket of buckets) {
      if (bucket.length) {
        deck.push(bucket.shift());
      }
    }
  }
  return deck;
}

function takeNextSpeakers(deck, seenSpeakerIds, count) {
  const seen = new Set(seenSpeakerIds);
  const selected = [];
  for (const speakerId of deck) {
    if (seen.has(speakerId)) {
      continue;
    }
    selected.push(speakerId);
    if (selected.length >= count) {
      break;
    }
  }
  return selected;
}

function toggleExcluded(modelId, speakerId, title, meta) {
  const excluded = excludedForModel(modelId);
  const alreadyExcluded = isExcluded(modelId, speakerId);
  const nextExcluded = alreadyExcluded
    ? excluded.filter((item) => item.speakerId !== speakerId)
    : [{ speakerId, title, meta }, ...excluded];
  state.excludedByModel[modelId] = nextExcluded;
  persistJson(STORAGE_KEYS.excluded, state.excludedByModel);
  renderBatchState();
  renderExcluded();
  renderFavorites();
  refreshExcludeButtons();
  setStatus(
    alreadyExcluded ? `speaker ${speakerId} 제외를 해제했습니다.` : `speaker ${speakerId}를 제외 목록에 추가했습니다.`,
  );
}

function refreshExcludeButtons() {
  document.querySelectorAll(".result-card").forEach((card) => {
    const button = card.querySelector(".exclude-toggle");
    const modelId = card.dataset.modelId;
    const speakerId = Number(card.dataset.speakerId);
    if (!button || !modelId || Number.isNaN(speakerId)) {
      return;
    }
    const excluded = isExcluded(modelId, speakerId);
    button.textContent = excluded ? "제외됨" : "제외 목록 추가";
    button.classList.toggle("saved", excluded);
  });
  document.querySelectorAll(".favorite-card").forEach((card) => {
    const title = card.querySelector(".favorite-title")?.textContent || "";
    const speakerId = Number(title.split("speaker ").pop());
    const button = card.querySelector(".favorite-exclude");
    if (!button || Number.isNaN(speakerId)) {
      return;
    }
    const excluded = isExcluded(currentModel().id, speakerId);
    button.textContent = excluded ? "제외됨" : "제외 목록 추가";
    button.classList.toggle("saved", excluded);
  });
}

function isFavorite(favoriteId) {
  return state.favorites.some((item) => item.favoriteId === favoriteId);
}

function isExcluded(modelId, speakerId) {
  return excludedForModel(modelId).some((item) => item.speakerId === speakerId);
}

function setStatus(message, isError = false) {
  elements.statusLine.textContent = message;
  elements.statusLine.dataset.error = isError ? "true" : "false";
}

function loadJson(key, fallback) {
  try {
    return JSON.parse(localStorage.getItem(key) || JSON.stringify(fallback));
  } catch {
    return fallback;
  }
}

function persistJson(key, value) {
  localStorage.setItem(key, JSON.stringify(value));
}

function rotatePrompt() {
  const options = Array.from(elements.promptSelect.options);
  if (!options.length) {
    return;
  }
  const currentIndex = options.findIndex((option) => option.value === elements.promptSelect.value);
  const nextIndex = currentIndex >= 0 ? (currentIndex + 1) % options.length : 0;
  elements.promptSelect.value = options[nextIndex].value;
  const preset = state.config.textPresets.find((item) => item.id === elements.promptSelect.value);
  if (preset) {
    elements.textInput.value = preset.text;
  }
}

function blobToDataUrl(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}
