const STORAGE_KEYS = {
  favorites: "tts-preset-lab-favorites-v1",
  seen: "tts-preset-lab-seen-v1",
  excluded: "tts-preset-lab-excluded-v1",
  finalSelection: "tts-preset-lab-final-selection-v1",
};

const AUTO_PROMPT_VALUE = "__auto_rotate__";
const FAVORITES_DB_NAME = "tts-preset-lab-db";
const FAVORITES_STORE_NAME = "favorites";

const state = {
  config: null,
  selectedModelId: null,
  provider: "auto",
  favorites: [],
  seenByModel: loadJson(STORAGE_KEYS.seen, {}),
  excludedByModel: loadJson(STORAGE_KEYS.excluded, {}),
  finalSelectionByModel: loadJson(STORAGE_KEYS.finalSelection, {}),
  resultCount: 0,
  currentBatchCards: [],
  autoplayEnabled: true,
  autoRotatePromptEnabled: false,
  currentPromptIndex: 0,
  favoriteObjectUrls: [],
  selectedFavoriteSpeakerId: null,
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
  generateFavoriteBatchButton: document.getElementById("generate-favorite-batch-button"),
  generateNearbyBatchButton: document.getElementById("generate-nearby-batch-button"),
  replayBatchButton: document.getElementById("replay-batch-button"),
  resetSeenButton: document.getElementById("reset-seen-button"),
  playFavoritesButton: document.getElementById("play-favorites-button"),
  clearExcludedButton: document.getElementById("clear-excluded-button"),
  saveCurrentBatchButton: document.getElementById("save-current-batch-button"),
  clearResultsButton: document.getElementById("clear-results-button"),
  favoritesGrid: document.getElementById("favorites-grid"),
  favoritesEmpty: document.getElementById("favorites-empty"),
  excludedGrid: document.getElementById("excluded-grid"),
  excludedEmpty: document.getElementById("excluded-empty"),
  favoriteCountSummary: document.getElementById("favorite-count-summary"),
  favoriteSpeakerChips: document.getElementById("favorite-speaker-chips"),
  selectedFavoriteSummary: document.getElementById("selected-favorite-summary"),
  favoriteNearbyList: document.getElementById("favorite-nearby-list"),
  finalSelectionSummary: document.getElementById("final-selection-summary"),
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
  await initializeFavoriteStore();
  const response = await fetch("/api/config");
  state.config = await response.json();
  state.provider = state.config.defaultProvider;
  state.selectedModelId = state.config.models[1]?.id || state.config.models[0].id;
  state.currentPromptIndex = 0;
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
  renderFinalSelectionSummary([]);
  elements.saveCurrentBatchButton.disabled = state.currentBatchCards.length === 0;
  setStatus("준비 완료. 예시 문장을 고르고 새 10개 생성을 누르면 됩니다.");
}

function bindEvents() {
  elements.promptSelect.addEventListener("change", () => {
    if (elements.promptSelect.value === AUTO_PROMPT_VALUE) {
      state.autoRotatePromptEnabled = true;
      elements.autoRotatePromptToggle.checked = true;
      applyPromptByIndex(state.currentPromptIndex);
      return;
    }
    state.autoRotatePromptEnabled = false;
    elements.autoRotatePromptToggle.checked = false;
    const promptIndex = state.config.textPresets.findIndex((item) => item.id === elements.promptSelect.value);
    if (promptIndex >= 0) {
      applyPromptByIndex(promptIndex);
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
    if (state.autoRotatePromptEnabled) {
      elements.promptSelect.value = AUTO_PROMPT_VALUE;
      applyPromptByIndex(state.currentPromptIndex);
    } else {
      const activePreset = state.config.textPresets[state.currentPromptIndex];
      if (activePreset) {
        elements.promptSelect.value = activePreset.id;
      }
    }
  });

  elements.generateBatchButton.addEventListener("click", async () => {
    await generateNextBatch();
  });

  elements.generateFavoriteBatchButton.addEventListener("click", async () => {
    await generateFavoriteBatch();
  });

  elements.generateNearbyBatchButton.addEventListener("click", async () => {
    await generateNearbyBatch();
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
    elements.saveCurrentBatchButton.disabled = true;
    setStatus("최근 생성 결과를 비웠습니다.");
  });

  elements.saveCurrentBatchButton.addEventListener("click", async () => {
    await saveCurrentBatchAudio();
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
  const autoOption = document.createElement("option");
  autoOption.value = AUTO_PROMPT_VALUE;
  autoOption.textContent = "자동 순환 (생성마다 변경)";
  elements.promptSelect.appendChild(autoOption);
  for (const preset of state.config.textPresets) {
    const option = document.createElement("option");
    option.value = preset.id;
    option.textContent = preset.label;
    elements.promptSelect.appendChild(option);
  }
  const first = state.config.textPresets[0];
  if (first) {
    elements.promptSelect.value = first.id;
    applyPromptByIndex(0);
  }
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
  renderFavoriteInsights();
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
  elements.saveCurrentBatchButton.disabled = false;
  setStatus(`새 10개 생성 완료. 즐겨찾기로 저장하거나 그대로 연속 청취할 수 있습니다.`);
  await autoplayBatch(state.currentBatchCards);
}

async function generateFavoriteBatch() {
  const model = currentModel();
  const groupedFavorites = groupedFavoriteSpeakers(model.id);
  if (!groupedFavorites.length) {
    setStatus("먼저 즐겨찾기에서 번호를 하나 이상 저장해 주세요.", true);
    return;
  }
  if (state.autoRotatePromptEnabled) {
    rotatePrompt();
  }
  const activeText = elements.textInput.value.trim();
  if (!activeText) {
    setStatus("텍스트를 입력해 주세요.", true);
    return;
  }

  await generateTargetedBatch({
    speakerIds: groupedFavorites.map((item) => item.speakerId),
    text: activeText,
    statusLabel: `즐겨찾기 번호 ${groupedFavorites.length}개`,
    sourceButton: elements.generateFavoriteBatchButton,
  });
}

async function generateNearbyBatch() {
  const model = currentModel();
  if (state.selectedFavoriteSpeakerId == null) {
    setStatus("주변 번호를 생성하려면 먼저 즐겨찾기 번호를 선택해 주세요.", true);
    return;
  }
  if (state.autoRotatePromptEnabled) {
    rotatePrompt();
  }
  const activeText = elements.textInput.value.trim();
  if (!activeText) {
    setStatus("텍스트를 입력해 주세요.", true);
    return;
  }

  const nearbySpeakerIds = buildNearbySpeakerIds(model.totalSpeakers, state.selectedFavoriteSpeakerId);
  await generateTargetedBatch({
    speakerIds: nearbySpeakerIds,
    text: activeText,
    statusLabel: `주변 번호 ${nearbySpeakerIds.length}개`,
    sourceButton: elements.generateNearbyBatchButton,
  });
}

async function generateTargetedBatch({ speakerIds, text, statusLabel, sourceButton }) {
  const model = currentModel();
  if (!speakerIds.length) {
    setStatus("생성할 번호가 없습니다.", true);
    return;
  }

  sourceButton.disabled = true;
  state.currentBatchCards = [];
  clearResultCardsForFavoriteSpeakers(speakerIds);
  setStatus(`${statusLabel}를 생성합니다...`);

  for (const [index, speakerId] of speakerIds.entries()) {
    setStatus(`${index + 1}/${speakerIds.length} 생성 중 · speaker ${speakerId}`);
    const result = await synthesizeSpeaker(model, text, speakerId);
    if (!result) {
      sourceButton.disabled = false;
      return;
    }
    state.currentBatchCards.push(appendResultCard(result));
  }

  sourceButton.disabled = false;
  elements.saveCurrentBatchButton.disabled = false;
  setStatus(`${statusLabel} 생성 완료.`);
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
    await deleteFavoriteFromStore(result.favoriteId);
    button.textContent = "즐겨찾기 저장";
    button.classList.remove("saved");
    renderFavorites();
    return;
  }

  const favorite = {
    favoriteId: result.favoriteId,
    modelId: result.modelId,
    speakerId: result.speakerId,
    title: result.title,
    meta: result.meta,
    text: result.text,
    audioBlob: result.blob,
    createdAt: Date.now(),
  };
  try {
    await saveFavoriteToStore(favorite);
  } catch (error) {
    setStatus(`즐겨찾기 저장 실패: ${error.message}`, true);
    return;
  }
  state.favorites.unshift(favorite);
  button.textContent = "저장됨";
  button.classList.add("saved");
  renderFavorites();
  renderBatchState();
}

function renderFavorites() {
  revokeFavoriteObjectUrls();
  elements.favoritesGrid.innerHTML = "";
  const favoritesForModel = state.favorites.filter((item) => item.modelId === currentModel().id);
  elements.favoritesEmpty.classList.toggle("hidden", favoritesForModel.length > 0);
  elements.playFavoritesButton.disabled = favoritesForModel.length === 0;
  renderFinalSelectionSummary(favoritesForModel);

  for (const favorite of favoritesForModel) {
    const fragment = elements.favoriteCardTemplate.content.cloneNode(true);
    fragment.querySelector(".favorite-title").textContent = favorite.title;
    fragment.querySelector(".favorite-meta").textContent = favorite.meta;
    fragment.querySelector(".favorite-text").textContent = favorite.text;
    const audio = fragment.querySelector(".favorite-audio");
    const audioSource = favorite.audioBlob instanceof Blob ? URL.createObjectURL(favorite.audioBlob) : favorite.audioDataUrl;
    if (favorite.audioBlob instanceof Blob) {
      state.favoriteObjectUrls.push(audioSource);
    }
    audio.src = audioSource;
    audio.dataset.favoriteId = favorite.favoriteId;
    const finalButton = fragment.querySelector(".favorite-final-toggle");
    finalButton.textContent = isFinalSelected(favorite.modelId, favorite.speakerId) ? "최종 선택됨" : "최종 선택";
    finalButton.classList.toggle("saved", isFinalSelected(favorite.modelId, favorite.speakerId));
    finalButton.addEventListener("click", () => {
      toggleFinalSelection(favorite.modelId, favorite.speakerId, favorite.title);
    });
    const excludeButton = fragment.querySelector(".favorite-exclude");
    excludeButton.textContent = isExcluded(favorite.modelId, favorite.speakerId) ? "제외됨" : "제외 목록 추가";
    excludeButton.classList.toggle("saved", isExcluded(favorite.modelId, favorite.speakerId));
    excludeButton.addEventListener("click", () => {
      toggleExcluded(favorite.modelId, favorite.speakerId, favorite.title, favorite.meta);
    });
    fragment.querySelector(".favorite-remove").addEventListener("click", () => {
      removeFavorite(favorite.favoriteId);
    });
    elements.favoritesGrid.appendChild(fragment);
  }
}

async function removeFavorite(favoriteId) {
  const removedFavorite = state.favorites.find((item) => item.favoriteId === favoriteId);
  state.favorites = state.favorites.filter((item) => item.favoriteId !== favoriteId);
  await deleteFavoriteFromStore(favoriteId);
  if (removedFavorite && isFinalSelected(removedFavorite.modelId, removedFavorite.speakerId)) {
    delete state.finalSelectionByModel[removedFavorite.modelId];
    persistJson(STORAGE_KEYS.finalSelection, state.finalSelectionByModel);
  }
  updateFavoriteButtons(favoriteId);
  renderFavorites();
  renderBatchState();
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

function renderFavoriteInsights() {
  const model = currentModel();
  const groupedFavorites = groupedFavoriteSpeakers(model.id);
  const totalFavoriteSelections = groupedFavorites.reduce((sum, item) => sum + item.count, 0);
  elements.favoriteSpeakerChips.innerHTML = "";
  elements.generateFavoriteBatchButton.disabled = groupedFavorites.length === 0;
  elements.generateNearbyBatchButton.disabled = groupedFavorites.length === 0;

  if (!groupedFavorites.length) {
    state.selectedFavoriteSpeakerId = null;
    elements.favoriteCountSummary.textContent = "저장된 즐겨찾기 번호가 없습니다.";
    elements.selectedFavoriteSummary.textContent = "선택된 번호 없음";
    elements.favoriteNearbyList.innerHTML = "";
    return;
  }

  const validSelected = groupedFavorites.some((item) => item.speakerId === state.selectedFavoriteSpeakerId);
  if (!validSelected) {
    state.selectedFavoriteSpeakerId = groupedFavorites[0].speakerId;
  }

  elements.favoriteCountSummary.textContent = `번호 ${groupedFavorites.length}개 · 저장 ${totalFavoriteSelections}건`;
  for (const entry of groupedFavorites) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `mini-chip ${entry.speakerId === state.selectedFavoriteSpeakerId ? "active" : ""}`;
    button.innerHTML = `<span>#${entry.speakerId}</span><span class="mini-chip-count">x${entry.count}</span>`;
    button.addEventListener("click", () => {
      state.selectedFavoriteSpeakerId = entry.speakerId;
      renderFavoriteInsights();
    });
    elements.favoriteSpeakerChips.appendChild(button);
  }

  renderNearbyFavoriteNumbers(model.totalSpeakers, state.selectedFavoriteSpeakerId);
}

function renderNearbyFavoriteNumbers(totalSpeakers, centerSpeakerId) {
  elements.favoriteNearbyList.innerHTML = "";
  if (centerSpeakerId == null) {
    elements.selectedFavoriteSummary.textContent = "선택된 번호 없음";
    return;
  }
  const nearbySpeakerIds = buildNearbySpeakerIds(totalSpeakers, centerSpeakerId);
  const finalSelectedSpeakerId = finalSelectionSpeakerId(currentModel().id);
  elements.selectedFavoriteSummary.textContent = `선택 #${centerSpeakerId} 주변 번호`;
  for (const speakerId of nearbySpeakerIds) {
    const tag = document.createElement("span");
    tag.className = `mini-chip nearby-chip ${speakerId === centerSpeakerId ? "active" : ""} ${finalSelectedSpeakerId === speakerId ? "saved" : ""}`;
    tag.textContent = `#${speakerId}`;
    elements.favoriteNearbyList.appendChild(tag);
  }
}

function renderFinalSelectionSummary(favoritesForModel) {
  const selectedSpeakerId = finalSelectionSpeakerId(currentModel().id);
  if (selectedSpeakerId == null) {
    elements.finalSelectionSummary.textContent = "아직 최종 선택이 없습니다.";
    return;
  }
  const favorite = favoritesForModel.find((item) => item.speakerId === selectedSpeakerId);
  elements.finalSelectionSummary.textContent = favorite
    ? `${favorite.title} · 최종 선택됨`
    : `speaker ${selectedSpeakerId} · 최종 선택됨`;
}

function toggleFinalSelection(modelId, speakerId, title) {
  if (isFinalSelected(modelId, speakerId)) {
    delete state.finalSelectionByModel[modelId];
    persistJson(STORAGE_KEYS.finalSelection, state.finalSelectionByModel);
    renderFavorites();
    renderFavoriteInsights();
    setStatus(`${title} 최종 선택을 해제했습니다.`);
    return;
  }
  state.finalSelectionByModel[modelId] = speakerId;
  persistJson(STORAGE_KEYS.finalSelection, state.finalSelectionByModel);
  renderFavorites();
  renderFavoriteInsights();
  setStatus(`${title}를 최종 선택으로 지정했습니다.`);
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

function groupedFavoriteSpeakers(modelId) {
  const counts = new Map();
  for (const favorite of state.favorites.filter((item) => item.modelId === modelId)) {
    counts.set(favorite.speakerId, (counts.get(favorite.speakerId) || 0) + 1);
  }
  return [...counts.entries()]
    .map(([speakerId, count]) => ({ speakerId, count }))
    .sort((left, right) => right.count - left.count || left.speakerId - right.speakerId);
}

function finalSelectionSpeakerId(modelId) {
  const value = state.finalSelectionByModel[modelId];
  return typeof value === "number" ? value : null;
}

function isFinalSelected(modelId, speakerId) {
  return finalSelectionSpeakerId(modelId) === speakerId;
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

async function saveCurrentBatchAudio() {
  if (!state.currentBatchCards.length) {
    setStatus("먼저 생성된 결과가 있어야 저장할 수 있습니다.", true);
    return;
  }

  const files = state.currentBatchCards.map((entry, index) => ({
    name: buildBatchFilename(entry, index),
    blob: entry.blob,
  }));

  try {
    if (typeof window.showDirectoryPicker === "function") {
      const directoryHandle = await window.showDirectoryPicker({ mode: "readwrite" });
      for (const file of files) {
        const fileHandle = await directoryHandle.getFileHandle(file.name, { create: true });
        const writable = await fileHandle.createWritable();
        await writable.write(file.blob);
        await writable.close();
      }
      setStatus(`이번 생성 오디오 ${files.length}개를 선택한 폴더에 저장했습니다.`);
      return;
    }

    for (const file of files) {
      triggerBlobDownload(file.blob, file.name);
    }
    setStatus(`이번 생성 오디오 ${files.length}개를 다운로드했습니다.`);
  } catch (error) {
    if (error?.name === "AbortError") {
      setStatus("저장을 취소했습니다.", true);
      return;
    }
    setStatus(`오디오 저장 실패: ${error.message}`, true);
  }
}

function buildBatchFilename(entry, index) {
  const safeTitle = entry.title
    .replace(/\s+/g, "_")
    .replace(/[^a-zA-Z0-9_\-\.가-힣]/g, "");
  return `${String(index + 1).padStart(2, "0")}_${safeTitle}.wav`;
}

function triggerBlobDownload(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
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
  if (!state.config.textPresets.length) {
    return;
  }
  let nextIndex = state.currentPromptIndex;
  if (state.config.textPresets.length > 1) {
    while (nextIndex === state.currentPromptIndex) {
      nextIndex = Math.floor(Math.random() * state.config.textPresets.length);
    }
  }
  applyPromptByIndex(nextIndex);
  if (state.autoRotatePromptEnabled) {
    elements.promptSelect.value = AUTO_PROMPT_VALUE;
  }
}

function applyPromptByIndex(index) {
  const presets = state.config.textPresets;
  if (!presets.length) {
    return;
  }
  const normalizedIndex = ((index % presets.length) + presets.length) % presets.length;
  const preset = presets[normalizedIndex];
  state.currentPromptIndex = normalizedIndex;
  elements.textInput.value = preset.text;
  if (!state.autoRotatePromptEnabled) {
    elements.promptSelect.value = preset.id;
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

async function initializeFavoriteStore() {
  await migrateLegacyFavorites();
  state.favorites = await loadFavoritesFromStore();
}

function openFavoritesDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(FAVORITES_DB_NAME, 1);
    request.addEventListener("upgradeneeded", () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(FAVORITES_STORE_NAME)) {
        db.createObjectStore(FAVORITES_STORE_NAME, { keyPath: "favoriteId" });
      }
    });
    request.addEventListener("success", () => resolve(request.result));
    request.addEventListener("error", () => reject(request.error || new Error("indexedDB open failed")));
  });
}

async function loadFavoritesFromStore() {
  const db = await openFavoritesDb();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(FAVORITES_STORE_NAME, "readonly");
    const store = transaction.objectStore(FAVORITES_STORE_NAME);
    const request = store.getAll();
    request.addEventListener("success", () => {
      const favorites = request.result || [];
      favorites.sort((left, right) => (right.createdAt || 0) - (left.createdAt || 0));
      resolve(favorites);
    });
    request.addEventListener("error", () => reject(request.error || new Error("favorites load failed")));
  });
}

async function saveFavoriteToStore(favorite) {
  const db = await openFavoritesDb();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(FAVORITES_STORE_NAME, "readwrite");
    transaction.addEventListener("complete", resolve);
    transaction.addEventListener("error", () => reject(transaction.error || new Error("favorite save failed")));
    transaction.objectStore(FAVORITES_STORE_NAME).put(favorite);
  });
}

async function deleteFavoriteFromStore(favoriteId) {
  const db = await openFavoritesDb();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(FAVORITES_STORE_NAME, "readwrite");
    transaction.addEventListener("complete", resolve);
    transaction.addEventListener("error", () => reject(transaction.error || new Error("favorite delete failed")));
    transaction.objectStore(FAVORITES_STORE_NAME).delete(favoriteId);
  });
}

async function migrateLegacyFavorites() {
  const legacyFavorites = loadJson(STORAGE_KEYS.favorites, []);
  if (!legacyFavorites.length) {
    return;
  }
  const existingFavorites = await loadFavoritesFromStore().catch(() => []);
  if (existingFavorites.length) {
    localStorage.removeItem(STORAGE_KEYS.favorites);
    return;
  }
  for (const favorite of legacyFavorites) {
    const migrated = { ...favorite };
    if (!migrated.audioBlob && migrated.audioDataUrl) {
      migrated.audioBlob = await fetch(migrated.audioDataUrl).then((response) => response.blob());
    }
    await saveFavoriteToStore(migrated);
  }
  localStorage.removeItem(STORAGE_KEYS.favorites);
}

function revokeFavoriteObjectUrls() {
  for (const url of state.favoriteObjectUrls) {
    URL.revokeObjectURL(url);
  }
  state.favoriteObjectUrls = [];
}

function clearResultCardsForFavoriteSpeakers(speakerIds) {
  const targetIds = new Set(speakerIds.map((speakerId) => String(speakerId)));
  document.querySelectorAll("#results-grid .result-card").forEach((card) => {
    if (targetIds.has(card.dataset.speakerId || "")) {
      card.remove();
    }
  });
}

function buildNearbySpeakerIds(totalSpeakers, centerSpeakerId) {
  const start = Math.max(0, centerSpeakerId - 5);
  const end = Math.min(totalSpeakers - 1, centerSpeakerId + 5);
  const speakerIds = [];
  for (let speakerId = start; speakerId <= end; speakerId += 1) {
    speakerIds.push(speakerId);
  }
  return speakerIds;
}
