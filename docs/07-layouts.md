# Категории рецептов из JSON

Категорию можно описать данными, без Java и без зависимости от мода. Файл кладётся в ресурспак, в jar мода или в сборку:

```
assets/<namespace>/ryscope_layouts/<имя>.json
```

Перечитывается по F3+T. Раскладка применяется, только если тип рецепта из поля `for` зарегистрирован, то есть мод установлен.

## Как это работает

Каждый рецепт игры умеет записать себя в JSON (тот же вид, что в датапаке). Раскладка берёт значения прямо из этого JSON по путям вроде `ingredients`, `results[0]`, `ingredients.top`. Поэтому RYScope не обращается к классам мода.

## Приоритет

1. Раскладка из чужого пространства имён (мод или сборка) важнее встроенной в RYScope. Если автор мода сам поставляет раскладку для своего типа рецепта, используется она.
2. Среди нескольких раскладок одного приоритета побеждает та, что позже по порядку ресурспаков.
3. Если раскладки для типа нет, рецепты показывает плагин мода через JEI, как раньше.

## Слияние с категорией JEI

Если `category` совпадает с id категории в JEI, вкладка одна: рецепты из датапака рисует RYScope, а те, что плагин мода создаёт сам (их в датапаке нет), дорисовывает JEI. Так сделано, например, для `create:spout_filling`. Поле `replaces` полностью скрывает перечисленные категории JEI; использовать его стоит, только когда в них нет ничего сверх датапака.

## Формат

```json
{
  "for": ["create:mixing"],
  "category": "create:mixing",
  "title": "create.recipe.mixing",
  "icon": "create:mechanical_mixer",
  "stations": ["create:mechanical_mixer", "create:basin"],
  "size": [152, 76],
  "order": 40,
  "widgets": [
    { "type": "slots", "source": "ingredients", "at": [0, 4], "columns": 3, "max": 9 },
    { "type": "arrow", "at": [65, 29], "width": 22 },
    { "type": "slots", "source": "results", "at": [92, 4], "columns": 3, "max": 9, "output": true },
    { "type": "text", "text": "{processing_time|ticks}", "at": [0, 67], "if": "processing_time" },
    { "type": "text", "text": "{heat_requirement|name}", "at": [64, 67], "if": "heat_requirement" }
  ]
}
```

| Поле | Смысл |
|---|---|
| `for` | типы рецептов (id из реестра `recipe_type`), обязательно |
| `category` | id категории; по умолчанию первый тип из `for` |
| `title` | ключ перевода или текст |
| `icon`, `stations` | id предметов: значок вкладки и станции |
| `size` | ширина и высота рецепта в пикселях GUI |
| `order` | порядок вкладки, меньше значит левее |
| `replaces` | id категорий JEI, которые надо скрыть |

### Виджеты

| `type` | Поля | Что делает |
|---|---|---|
| `slot` | `source`, `at`, `index`, `large`, `output`, `keep_empty` | один слот; `index` берёт элемент массива |
| `slots` | `source`, `at`, `columns`, `max`, `from`, `pitch_x`, `pitch_y`, `output` | сетка слотов из массива |
| `arrow` | `at`, `width` | стрелка |
| `flame`, `plus` | `at` | значки |
| `text` | `text`, `at`, `args` | текст с подстановками |

У любого виджета есть условия: `"if": "путь"` (поле есть и не пустое), `"unless": "путь"`, а также `"if"` вместе с `"equals": "значение"`.

### Что понимается как ингредиент

- `{"item": "id"}`, `{"tag": "ns:tag"}`, строка `"id"` или `"#tag"`, массив вариантов;
- стек `{"id": "...", "count": 2, "chance": 0.25}`: шанс рисуется процентом на слоте;
- жидкость `{"fluid": "...", "amount": 250}`, `{"amount": 250, "tag": "c:honey"}`, `{"id": "minecraft:water", "amount": 1000}`;
- обёртки `{"ingredient": {...}, "count": 2}`, `neoforge:compound`, `neoforge:difference` (берётся основа).

Химикаты Mekanism и другие особые типы ресурсов так не читаются: для них остаётся плагин мода.

### Подстановки в тексте

`{путь}` или `{путь|фильтр}`. Фильтры: `ticks` (тики в секунды), `percent`, `fe`, `mb`, `name` (значение перечисления, переводится ключом `ryscope.value.<значение>`). Если значения нет, текст не рисуется. Вместо шаблона можно дать ключ перевода в `text` и пути в `args`.

## Встроенные раскладки

Проверены в игре на Create 6.0.10, Mekanism 10.7.19, AE2 19.2.19 (NeoForge 1.21.1):

| Мод | Типы |
|---|---|
| Create | pressing, mixing, compacting, crushing, milling, cutting, deploying, filling, emptying, splashing, haunting, sandpaper_polishing, item_application |
| Mekanism | crushing, enriching, combining, sawing (машины без химикатов) |
| AE2 | inscriber, charger, transform |

У AE2 под 1.21.1 своего плагина JEI нет (он вынесен в отдельный мод), так что без этих раскладок его рецепты в связке с JEI не видны вовсе.

Не покрыто: sequenced_assembly и mechanical_crafting у Create, все машины Mekanism с химикатами, entropy и matter_cannon у AE2. Их показывает плагин мода, если он есть.
