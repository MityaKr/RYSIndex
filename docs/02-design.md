# RYScope: дизайн

Три направления нарисованы для одного и того же экрана (инвентарь игрока, панель предметов справа, карточка рецепта «Поршень» поверх, стикеры доски снизу, подсказка «Линзы» сверху). Исходники: `design/Main.dc.html`, `design/Vanilla.dc.html`, `design/Glass.dc.html`.

## Решение

- **Slate** (вариант A) идёт темой по умолчанию.
- **Vanilla Refined** (B) и **Glass** (C) идут встроенными темами, переключаются в настройках.
- Все три описываются одним форматом темы (черновики в `design/themes/*.json`), так что любой ресурспак может добавить свою.
- Пожелание автора: дизайны должны быть именно темами, не отдельными режимами.

## A · Slate (по умолчанию)

Тёмный графит, скругления 8–14 px, мягкие тени, янтарный акцент только на действиях и прогрессе. Спокойный, читается на любом фоне мира.

| Токен | Значение |
|---|---|
| panel.bg | #1c1f27 |
| panel.border | #2c313d |
| panel.radius | 12 |
| card.bg | #20242e |
| card.border | #343a48 |
| card.radius | 14 |
| slot.bg | #12141a |
| slot.border | #262a35 |
| slot.radius | 6 |
| slot.size | 40 (карточка 48, результат 64) |
| sidebar.bg | #16181f |
| input.bg | #0f1116 |
| text.primary | #f7f8fb |
| text.body | #e6e8ee |
| text.muted | #8b93a5 |
| text.dim | #5f6678 |
| accent | #e4b23c (альтернативы #5fc9a6, #8fb4ff, #f08a5d) |
| accent.text | #15181f |
| state.ok | #6fcf8f |
| state.warn | #e4b23c |
| state.missing | opacity 0.45 на слоте |
| chip.mod | bg #1f2a44, fg #8fb4ff |
| chip.fn | bg #2a1f44, fg #c7a6ff |
| overlay | rgba(14,16,22,0.55) поверх мира |
| font.display | Space Grotesk 600 |
| font.body | Inter 400/500/600 |
| shadow | 0 24px 60px rgba(0,0,0,.45) |

В игре: Space Grotesk и Inter заменяются на встроенный шрифт игры или TTF-шрифт темы через ресурспак (Minecraft поддерживает TTF-провайдеры шрифтов).

## B · Vanilla Refined

Честные бевели и серые панели как в игре, пиксельный шрифт, зелёная кнопка действия. Для игроков, которым важно, чтобы мод не выделялся из интерфейса Minecraft.

| Токен | Значение |
|---|---|
| panel.bg | #c6c6c6 |
| panel.bevel | light #ffffff, dark #555555, width 4 |
| slot.bg | #8b8b8b |
| slot.bevel | dark #373737 (верх/лево), light #ffffff (низ/право), width 2 |
| slot.radius | 0 |
| inset.bevel | обратный бевель (#555555 верх/лево) для вложенных областей |
| button.bg | #a0a0a0 |
| input.bg | #000000, text #e6e6e6 |
| text.primary | #3f3f3f |
| text.muted | #6b6b6b |
| accent | #3d8b3d (альтернативы #3a5fcf, #b8651a, #8b3d8b) |
| accent.text | #ffffff с тенью 1px #3a3a3a |
| tab.inactive | bg #8b8b8b, fg #e6e6e6 |
| hud.bg | rgba(16,16,16,0.78), border #5a5a5a |
| tooltip | bg rgba(16,0,16,0.9), border #28007f (как ванильный тултип) |
| overlay | rgba(16,16,16,0.62) |
| font | Pixelify Sans (в игре: стандартный шрифт игры) |

## C · Glass

Матовое стекло поверх мира, пилюли вместо вкладок, круглые кнопки, голубой акцент. Самый эффектный и самый спорный: на ярких биомах читаемость падает, blur дороже по рендеру. В игре blur реализуется шейдером (как размытие фона меню в 1.21), с фолбэком на более тёмную непрозрачную подложку при выключенном blur.

| Токен | Значение |
|---|---|
| panel.bg | rgba(255,255,255,0.12) |
| panel.border | rgba(255,255,255,0.35) |
| panel.radius | 18–22 |
| panel.blur | 18–22 px |
| card.bg | rgba(20,30,45,0.55) |
| slot.bg | rgba(0,0,0,0.28) |
| slot.border | rgba(255,255,255,0.18) |
| slot.radius | 8–10 |
| tab.active | pill, bg accent, fg #10233a |
| tab.inactive | pill, bg rgba(255,255,255,0.12), border rgba(255,255,255,0.25) |
| button | pill (radius 999) |
| text.primary | #ffffff |
| text.muted | rgba(255,255,255,0.75) |
| accent | #7fe3ff (альтернативы #ffd166, #b6ffc9, #ffb3c7) |
| state.ok | #b6ffc9 |
| state.warn | #ffd166 |
| overlay | rgba(10,20,30,0.28) + радиальный блик |
| font | Manrope 400–700 |

## Общие правила для всех тем

- Один и тот же DOM/виджет-дерево, меняются только токены и текстуры: тема не может менять раскладку.
- Недостающий ингредиент всегда показан полупрозрачным слотом плюс текстом, не только цветом.
- Шанс выпадения: бейдж в углу слота с процентом, не только мерцание.
- Минимальная зона клика 44 px в режиме Comfortable, 32 px в Compact.
- Контраст текста не ниже 4.5:1 на фоне панели для основного текста и 3:1 для вторичного.
- Каждая тема объявляет и светлую подложку для подсказок (тултипов), чтобы читалось над миром.

## Формат темы (черновик)

```json
{
  "id": "ryscope:slate",
  "name": "Slate",
  "font": "ryscope:inter",
  "colors": { "panel.bg": "#1c1f27", "accent": "#e4b23c", "...": "..." },
  "metrics": { "panel.radius": 12, "slot.size": 18, "slot.radius": 6, "bevel": 0 },
  "textures": { "slot": "ryscope:textures/gui/slate/slot.png", "panel": "ryscope:textures/gui/slate/panel.png" },
  "effects": { "blur": 0, "shadow": true, "animations": "normal" },
  "overlay": "#0e1016 55%"
}
```

Полные черновики трёх тем: `design/themes/`.

## Что ещё не нарисовано

Экраны графа крафта, источников, планировщика, сравнения, линзы в мире, dev-режима есть только как вайрфреймы в `design/concept-page.html`. Следующий шаг дизайна: довести их в стиле Slate.
