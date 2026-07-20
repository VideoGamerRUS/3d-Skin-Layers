# CLAUDE.md — форк 3d-Skin-Layers с поддержкой bendy-lib (Emotecraft)

## Цель форка

Апстрим: `tr7zw/3d-skin-layers`, ветка `main`, версия мода **1.11.2**.
Единственная задача: совместимость с **Emotecraft 2.4.12 / bendy-lib 5.1** —
сейчас 3D-слои скина не следуют за изгибом конечностей (bend) во время эмоций.
Известный открытый баг с 2021 г. (апстрим issues #13, #192; emotecraft #508).

Целевая платформа: **NeoForge 1.21.1** (модпак «Пульс», раздача через Automodpack).
Остальные версии из матрицы не трогаем и не собираем.

## Архитектура репозитория

- `3dSkinLayers-Versionless/` — версионно-независимое ядро (чистая Java, без Minecraft-классов):
  - `versionless/render/CustomModelPart.java`, `CustomizableCube.java` — геометрия 3D-слоя
    («объёмные пиксели», меш прекомпьютится один раз на скин).
- `src/main/java/dev/tr7zw/skinlayers/` — общий MC-код с препроцессором:
  - `render/CustomizableModelPart.java` — рантайм-обёртка части слоя.
    **`copyFrom(ModelPart)` копирует с ванильной части ТОЛЬКО pos/rot/scale — bend теряется здесь.**
  - `renderlayers/CustomLayerFeatureRenderer.java` — feature renderer, решает, что рисовать на каждой части тела.
  - `mixin/` — см. `src/main/resources/skinlayers3d.mixins.json` (только client-миксины).
    Прецеденты компат-шимов, на которые равняться: `EMFModelPartMixin` (@Pseudo-миксин
    в чужой класс EMF) и `util/SodiumWorkaround.java`.
- `versions/1.21.1-neoforge/` — наша целевая сборка (существует в апстриме).
- Мультиверсионность через препроцессор-комментарии вида `//? if >= 1.21.0 && neoforge {`.
  Новый код писать так, чтобы компилировался в 1.21.1-neoforge; гейтить препроцессором при необходимости.
- Сборка: `gradle-compose` (обёртка `gradlecw` / `gradlecw.bat`), шаблон tr7zw ProcessedModTemplate.
  Если докеризованный gradlecw не взлетит на Windows — собирать подпроект версии напрямую обычным gradle.

## Механика бага (важно понимать)

bendy-lib НЕ меняет xRot/yRot/zRot у ModelPart. Он подменяет кубоиды внутри ModelPart
на `BendableCuboid` и гнёт их вершины. Skinlayers же строит СВОЙ меш слоя и позиционирует
его по PartPose (pos/rot/scale) через `CustomizableModelPart.copyFrom()` — изгиб
базовой конечности есть, а слой поверх остаётся прямым / отъезжает.

Результаты бисекта на целевом паке (июль 2026), считать установленными фактами:
- Виновник изолирован: скин-слои ломаются при бендовых эмоциях с skinlayers БЕЗ EMF/ETF.
- Без skinlayers (с EMF+ETF и без них) всё работает корректно — EMF/ETF вне подозрений,
  спец-совместимость с ними в рамках этого форка НЕ нужна.
- Sodium уже обезврежен на уровне пака (`sodium-mixins.properties`,
  `mixin.features.render.entity=false`) — сгибание базовых конечностей работает.
  Форк решает ТОЛЬКО проблему слоёв поверх согнутых частей.

## API bendy-lib 5.1 (проверено по исходникам, ветка 1.21)

Пакет `io.github.kosmx.bendylib`:

- `ModelPartAccessor.optionalGetCuboid(ModelPart part, int index)` → `Optional<MutableCuboid>` —
  безопасно достать кубоид части (index 0 для конечностей).
- `MutableCuboid.getActiveMutator()` / проверка `instanceof BendableCuboid`.
- `BendableCuboid`:
  - `float getBend()` — текущий угол сгиба (радианы)
  - `float getBendAxis()` — ось сгиба
  - `Direction getBendDirection()`, `getBendX/Y/Z()` — пивот сгиба
  - `Matrix4f applyBend(float axis, float value)` — возвращает матрицу трансформации
  - `Plane getBasePlane()` / `getOtherSidePlane()`, `bendHeight()`
- Устаревший `setRotationDeg(axis, deg)` — не использовать, только чтение состояния.

Зависимость строго **optional**: bendy-lib едет jar-in-jar внутри Emotecraft и может
отсутствовать. Все обращения — через @Pseudo-миксин или reflection-шим по образцу
`EMFModelPartMixin`; форк обязан работать без Emotecraft.

## План работ

### Фаза A — прагматичный фикс (страховка, делается первой)
Во время активной bend-анимации не рисовать 3D-слой на гнущихся частях и возвращать
видимость ванильного 2D-слоя (или скрывать слой целиком — выбрать по результатам теста).
- Детект: у части тела `optionalGetCuboid(...)` → `BendableCuboid.getBend() != 0`
  (или API playerAnimator: активный слой анимации игрока).
- Точка врезки: `CustomLayerFeatureRenderer` перед рендером каждой части.
- Критерий готовности: эмоция с бендом — без визуальных артефактов; обычная стойка — 3D-слои на месте.

### Фаза B — честный бенд меша (после Фазы A, кандидат в PR апстриму)
Применять к вершинам меша слоя ту же деформацию, что bendy-lib применяет к базовому кубоиду:
- Прочитать у `BendableCuboid` угол/ось/пивот (или взять готовую `Matrix4f` из `applyBend`).
- В `CustomizableCube`/`CustomModelPart` трансформировать вершины ниже плоскости пивота.
- Внимание на кэш: меши слоёв прекомпьютятся на скин — деформацию применять в рантайме
  при рендере (poseStack/повершинно), НЕ мутировать кэшированный меш.
- Критерий готовности: рукав визуально гнётся вместе с рукой, шов на пивоте без дыр.

## Ограничения и договорённости

- **Лицензия**: tr7zw Protective License — модификация разрешена, коммерческое
  использование запрещено, копирайт-нотис сохранять во всех копиях. LICENSE не трогать.
- **modid `skinlayers3d` НЕ менять** (совместимость с конфигами и mixin-таргетами других
  модов). Версия помечается суффиксом: `1.11.2-pulse.1`.
- Раздача: заменить jar в серверной раздаче Automodpack, иначе при сверке хэшей
  клиентам вернётся оригинал.
- Тестовый стек совместимости (обязательный прогон): Sodium 0.6.x/1.21.1
  (+ `sodium-mixins.properties`: `mixin.features.render.entity=false`), Iris 1.8.14-beta,
  EMF 3.2.4 + ETF 7.1, Emotecraft 2.4.12, BadOptimizations (entity_renderer_caching выкл).
- Тестировать бенд ТОЛЬКО в F5 или на втором клиенте (от первого лица бенд не виден).
- Тестовые эмоции: только валидные (часть эмоций пака отбраковывается на отрицательных
  тиках — их не использовать как индикатор).

## Команды

```bash
# сборка целевой версии (проверено, работает; докер НЕ нужен —
# gradlecw сначала запускает gradle/gradle-compose.jar для генерации
# билд-файлов из шаблона, затем обычный gradle wrapper)
./gradlecw :1.21.1-neoforge:build --no-daemon --configure-on-demand
# --configure-on-demand обязателен: без него конфигурируются все ~40 версий
# матрицы и каждая качает свой Minecraft (падает при нестабильной сети)
```

Артефакт: `versions/1.21.1-neoforge/build/libs/`.
