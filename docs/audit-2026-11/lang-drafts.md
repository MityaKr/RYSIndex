# Черновики текстов для игроков после аудита

Новые и изменённые строки. Слева ключ, затем ru и en. Править прямо в lang-файлах или напишите, что поменять.

## RYScope: новых 124, изменённых 12, удалённых 0

| ключ | ru | en |
|---|---|---|
| `ryscope.bookmark.already` | Уже в закладках | Already bookmarked |
| `ryscope.recipes.only_sources` | Использований нет, показаны источники | No uses, showing sources |
| `ryscope.recipes.only_help` | Использований нет, показана справка | No uses, showing help |
| `ryscope.tree.from_surplus` | из остатка другого крафта | left over from another craft |
| `ryscope.tree.truncated` | не раскрыто · клик раскроет | not expanded · click to expand |
| `ryscope.tree.truncated.tip` | Есть рецепт: ветка не раскрыта, раскройте её в дереве | Has a recipe: the branch is not expanded, expand it in the tree |
| `ryscope.tree.autocraft` | Автокрафт AE2 | AE2 autocraft |
| `ryscope.tree.autocraft.tip` | Не раскрывать то, что сеть AE2 умеет делать сама, а заказать в ней. По данным терминалов, которые вы открывали в этом мире | Don't expand what your AE2 network can craft by itself, order it there instead. Based on the terminals you opened in this world |
| `ryscope.tree.ordered` | закажет сеть %s | network crafts %s |
| `ryscope.tree.to_order` | Заказать в сети: %s | Order from network: %s |
| `ryscope.tree.order_at` | по данным на %s | as of %s |
| `ryscope.tree.all_have_order` | Остальное есть, недостающее закажет сеть | You have the rest, the network crafts what's missing |
| `ryscope.board.ordered` | из них %s закажет сеть | %s via network |
| `ryscope.tree.all_goals` | Все цели | All goals |
| `ryscope.tree.all_tree` | Дерево целей | Goal tree |
| `ryscope.tree.all_summary` | Целей: %s · Шагов: %s | Goals: %s · Steps: %s |
| `ryscope.tree.all_incomplete` | неполный | incomplete |
| `ryscope.tree.all_incomplete.tip` | Итог неполный: дерево упёрлось в предел узлов | Incomplete total: the tree hit its node limit |
| `ryscope.tree.all_none` | Целей нет: закрепите предмет кнопкой в дереве крафта | No goals: pin an item with the button in the crafting tree |
| `ryscope.tree.all_note` | Общие промежуточные предметы считаются один раз, сумма может быть меньше суммы стикеров | Shared intermediate items are counted once, so the total can be less than the stickers add up to |
| `ryscope.tree.route_from` | маршрут из цели «%s» | route from goal "%s" |
| `ryscope.tree.to_chat` | В чат | To chat |
| `ryscope.tree.col.item` | Предмет | Item |
| `ryscope.tree.col.need` | Нужно | Need |
| `ryscope.tree.col.inventory` | Инвентарь | Inventory |
| `ryscope.tree.col.storage` | Хранилища | Storages |
| `ryscope.tree.col.order` | Сеть | Network |
| `ryscope.tree.col.left` | Осталось | Left |
| `ryscope.tree.need_stations` | Нужны станции: %s | Stations needed: %s |
| `ryscope.tree.no_station` | Станции нет: %s | No station: %s |
| `ryscope.tree.no_station.tip` | Правый клик по значку категории в окне рецептов отмечает, что станция у вас есть | Right-click the category icon in the recipe window to mark that you have the station |
| `ryscope.stations.owned` | %s: отмечено, что станция есть | %s: marked as available |
| `ryscope.stations.missing` | %s: отмечено, что станции нет | %s: marked as missing |
| `ryscope.stations.auto` | %s: станция определяется сама | %s: detected automatically |
| `ryscope.stations.tip.owned` | Станция есть | You have the station |
| `ryscope.stations.tip.owned_mark` | Станция есть: отмечено вами | You have the station: marked by you |
| `ryscope.stations.tip.missing` | Станции нет: дерево крафта выбирает этот способ последним | No station: the crafting tree picks this method last |
| `ryscope.stations.tip.missing_mark` | Станции нет: отмечено вами | No station: marked by you |
| `ryscope.stations.tip.click` | Правый клик: есть, нет, определять самому | Right-click: have it, missing, detect automatically |
| `ryscope.settings.sticker` | Стикер целей | Goal stickers |
| `ryscope.settings.sticker.goals` | По целям | Per goal |
| `ryscope.settings.sticker.sum` | Сумма | Total |
| `ryscope.settings.sticker.note` | Сумма: один стикер «Все цели» с общим сырьём, клик открывает таблицу | Total: one "All goals" sticker with the combined raw materials, click opens the table |
| `ryscope.tree.update_goal` | Обновить цель | Update goal |
| `ryscope.tree.unpin_short` | Снять | Unpin |
| `ryscope.board.updated` | Цель обновлена | Goal updated |
| `ryscope.board.unsupported` | Целью можно закрепить только предмет | Only items can be pinned as goals |
| `ryscope.board.computing` | Считаю сырьё | Calculating |
| `ryscope.help.11` | in:X — рецепты категории X | in:X — recipes of category X |
| `ryscope.help.12` | drop:X — выпадает из моба или блока X | drop:X — dropped by mob or block X |
| `ryscope.help.13` | loot:X — есть в добыче X, в том числе в сундуках | loot:X — found in loot X, chests included |
| `ryscope.help.14` | craftable, almost — всё есть, не хватает одного | craftable, almost — have everything, one missing |
| `ryscope.help.15` | locked, unlocked, stage:X — по стадиям | locked, unlocked, stage:X — by stage |
| `ryscope.help.16` | *id, /regex/, "фраза" — по id, выражению, фразе целиком | *id, /regex/, "phrase" — by id, expression, exact phrase |
| `ryscope.hidden.too_many` | Запрос находит %s из %s, скрывать не стали: уточните его | The search finds %s of %s, nothing was hidden: narrow it down |
| `ryscope.hidden.undone` | Снова в списке: %s | Back in the list: %s |
| `ryscope.hidden.already` | Уже скрыто: %s | Already hidden: %s |
| `ryscope.hidden.not_ready` | Список ещё загружается, нажмите Ctrl+H чуть позже | The list is still loading, try Ctrl+H again in a moment |
| `ryscope.list.empty` | Ничего не найдено | Nothing found |
| `ryscope.list.empty.craftable` | Фильтр «Доступно» оставляет только то, что можно сделать сейчас | The Craftable filter keeps only what you can make now |
| `ryscope.list.empty.hidden` | Скрыто запросами: %s, вернуть можно в настройках | Hidden by searches: %s, restore them in settings |
| `ryscope.list.empty.query` | Проверьте запрос: значок «?» подскажет язык поиска | Check the search: the ? icon explains the syntax |
| `ryscope.list.no_room` | Списку не хватает места: уменьшите «Размер интерфейса RYS» в настройках | Not enough room for the list: lower "RYS interface size" in settings |
| `ryscope.overlay.hidden` | Панели скрыты, %s вернёт их | Panels hidden, %s brings them back |
| `ryscope.overlay.shown` | Панели снова видны | Panels are back |
| `ryscope.settings.hidden.none` | Скрытых запросов нет: Ctrl+H в списке скрывает найденное | No hidden searches: Ctrl+H in the list hides the results |
| `ryscope.settings.hidden.title` | Крестик возвращает запрос в список | The cross puts a search back into the list |
| `ryscope.settings.hidden.restore_all` | Вернуть все | Restore all |
| `ryscope.settings.hidden.confirm` | Нажмите ещё раз, чтобы вернуть все запросы: %s | Click again to restore all searches: %s |
| `ryscope.settings.show_hidden` | Показывать скрытое | Show hidden |
| `ryscope.settings.show_hidden.note` | Предметы, скрытые запросами или клавишей скрытия, снова видны в списке; клавиша скрытия над предметом возвращает его | Items hidden by searches or the hide key are visible in the list again; the hide key over an item brings it back |
| `ryscope.compare.count_of` | Способов получить: %s, показаны первые %s | Ways to make it: %s, showing the first %s |
| `ryscope.compare.none` | Получить этот предмет нечем | There is no way to make this |
| `ryscope.compare.pick` | Клик: выбрать этот маршрут в дереве | Click: use this route in the tree |
| `ryscope.ore.cached` | по данным одиночной игры | from singleplayer data |
| `ryscope.ore.unknown` | Руды видны после захода в одиночный мир с этой сборкой | Ores appear after you join a singleplayer world with this modpack |
| `ryscope.settings.reports.failed` | Отчёты не собраны: подробности в логе | Reports failed: see the log |
| `ryscope.click_area.show` | Показать рецепты | Show recipes |
| `ryscope.recipes.category_all` | Все рецепты категории | All recipes in this category |
| `key.ryscope.hide` | Скрыть предмет из списка (Shift: все варианты) | Hide item from the list (Shift: all variants) |
| `key.ryscope.hide_query` | Скрыть всё найденное запросом | Hide everything the search found |
| `key.ryscope.save_search` | Сохранить запрос | Save search |
| `key.ryscope.copy_id` | Копировать id | Copy id |
| `key.ryscope.next_tab` | Следующая вкладка истории (Shift: предыдущая) | Next history tab (Shift: previous) |
| `key.ryscope.close_tab` | Закрыть вкладку истории | Close history tab |
| `key.ryscope.prev_page` | Предыдущая страница | Previous page |
| `key.ryscope.next_page` | Следующая страница | Next page |
| `key.ryscope.prev_category` | Предыдущая категория рецептов | Previous recipe category |
| `key.ryscope.next_category` | Следующая категория рецептов | Next recipe category |
| `key.ryscope.forward` | Вперёд | Forward |
| `key.ryscope.share` | Показать предмет в чате | Share item in chat |
| `key.ryscope.craft` | Разложить рецепт в сетку (Shift: максимум) | Fill the grid with the recipe (Shift: as many as possible) |
| `ryscope.hidden.item` | Скрыто: %s. Вернуть: «Показывать скрытое» в настройках и та же клавиша | Hidden: %s. To bring it back, turn on “Show hidden” in settings and press the key again |
| `ryscope.hidden.item.all` | Скрыты все варианты: %s. Вернуть: «Показывать скрытое» в настройках и та же клавиша | All variants hidden: %s. To bring them back, turn on “Show hidden” in settings and press the key again |
| `ryscope.hidden.item.shown` | Снова в списке: %s | Back in the list: %s |
| `ryscope.recipes.stations` | Станции: %s | Stations: %s |
| `ryscope.ing.tag` | Любой из %s (%s) | Any of %s (%s) |
| `ryscope.ing.options` | Один из %s вариантов | One of %s variants |
| `ryscope.ing.hint` | Shift: остановить перебор · Ctrl+клик: все варианты | Shift: pause cycling · Ctrl+click: all variants |
| `ryscope.recipe_mark.add` | Этот рецепт в закладки | Bookmark this recipe |
| `ryscope.recipe_mark.in` | Рецепт в закладках (клик убирает) | Recipe bookmarked (click to remove) |
| `ryscope.recipe_mark.added` | Рецепт добавлен в закладки | Recipe bookmarked |
| `ryscope.recipe_mark.removed` | Рецепт убран из закладок | Recipe bookmark removed |
| `ryscope.recipe_mark.tooltip` | Закладка на рецепт: %s | Recipe bookmark: %s |
| `ryscope.recipe_mark.missing` | Рецепта больше нет: %s | Recipe no longer exists: %s |
| `ryscope.recipe_mark.gone` | Рецепта из закладки больше нет в сборке | The bookmarked recipe is no longer in the pack |
| `ryscope.recipe_default.make` | Делать этим рецептом: дерево крафта и быстрый крафт возьмут его | Make it with this recipe: the craft tree and quick craft will use it |
| `ryscope.recipe_default.is` | Рецепт по умолчанию (клик снимает) | Default recipe (click to clear) |
| `ryscope.recipe_default.set` | %s теперь делается этим рецептом | %s is now made with this recipe |
| `ryscope.recipe_default.cleared` | Рецепт по умолчанию для «%s» снят | Default recipe for “%s” cleared |
| `ryscope.craft.none` | Для «%s» нет рецепта, который раскладывается в это окно | No recipe for “%s” fits this window |
| `ryscope.cheat.denied` | Выдавать предметы можно в творческом режиме, а оператору и в выживании, если RYScope стоит на сервере | Items can be given in creative mode, or to an operator in survival when RYScope is installed on the server |
| `ryscope.cheat.delete` | Клик: убрать предмет с курсора | Click: delete the item on the cursor |
| `key.ryscope.shot` | Снимок рецепта в PNG | Save recipe as PNG |
| `ryscope.settings.sort` | Порядок списка | List order |
| `ryscope.settings.sort.mod` | по модам | by mod |
| `ryscope.settings.sort.name` | по названию | by name |
| `ryscope.settings.sort.source` | как в JEI без сортировки | as JEI lists them |
| `ryscope.settings.sort.note` | По модам: Minecraft первым, затем моды по имени, жидкости и прочее рядом со своим модом | By mod: Minecraft first, then mods by name, with fluids and other types next to their mod |
| `ryscope.settings.mod_name` | Мод в подсказках | Mod name in tooltips |
| `ryscope.settings.mod_name.note` | Последней строкой подсказки в списке и карточке, если её не дописал другой мод | As the last tooltip line in the list and the card, unless another mod has added it |
| `ryscope.shot.saved` | Снимок рецепта сохранён: %s | Recipe image saved: %s |
| `ryscope.shot.toast` | Снимок рецепта сохранён в screenshots/ryscope | Recipe image saved to screenshots/ryscope |
| `ryscope.shot.failed` | Снимок рецепта не получился | Could not save the recipe image |
| `ryscope.recipes.hint` (изменён) | ЛКМ: рецепты · ПКМ: использования · СКМ: закладка · Колесо, стрелки влево и вправо, PgUp и PgDn: страницы · Стрелки вверх и вниз, Tab, Shift+PgUp и Shift+PgDn: категории · Backspace: назад · Ctrl+Tab: следующая вкладка · Ctrl+W или ПКМ по вкладке истории: закрыть вкладку · Ctrl+C: id рецепта · Ctrl+Shift+C: рецепт в JSON · Shift: остановить смену вариантов | LMB: recipes · RMB: uses · MMB: bookmark · Wheel, Left and Right, PgUp and PgDn: pages · Up and Down, Tab, Shift+PgUp and Shift+PgDn: categories · Backspace: back · Ctrl+Tab: next tab · Ctrl+W or RMB on a history tab: close tab · Ctrl+C: recipe id · Ctrl+Shift+C: recipe as JSON · Shift: pause cycling |
| `ryscope.settings.cheat` (изменён) | Выдавать предметы по клику | Give items on click |
| `ryscope.settings.cheat.note` (изменён) | В творческом режиме, а оператору и в выживании, если RYScope стоит на сервере. Клик кладёт предмет на курсор, Shift: стак, Alt: в инвентарь; клик предметом с курсора по списку убирает его. Вне творческого инвентаря курсор и удаление работают только с RYScope на сервере, иначе предмет идёт в инвентарь. Из карточки рецептов предмет всегда идёт в инвентарь | In creative mode, or for an operator in survival when RYScope is installed on the server. A click puts the item on the cursor, Shift: a full stack, Alt: into the inventory; clicking the list with an item on the cursor deletes it. Outside the creative inventory the cursor and deleting need RYScope on the server, otherwise the item goes into the inventory. From the recipe card items always go into the inventory |
| `ryscope.settings.footer` (изменён) | Клавиши: Настройки → Управление → Назначение клавиш → RYScope | Keys: Options → Controls → Key Binds → RYScope |
| `ryscope.settings.board.note` (изменён) | Показывает, чего ещё не хватает для закреплённых целей: булавка у рецепта или кнопка в дереве крафта | Shows what is still missing for pinned goals: the pin next to a recipe or the button in the crafting tree |
| `ryscope.hidden.added` (изменён) | Скрыто из списка: %s · Ctrl+Z вернёт | Hidden from the list: %s · Ctrl+Z to undo |
| `ryscope.settings.hidden` (изменён) | Скрытые запросы | Hidden searches |
| `ryscope.goals.add` (изменён) | Закрепить цель | Pin as goal |
| `ryscope.goals.in` (изменён) | Цель закреплена | Goal pinned |
| `ryscope.goals.added` (изменён) | Цель закреплена | Goal pinned |
| `ryscope.section.graph` (изменён) | Дерево | Tree |
| `ryscope.goals.removed` (изменён) | Цель снята | Goal removed |

## RYSTeams: новых 20, изменённых 4, удалённых 10

| ключ | ru | en |
|---|---|---|
| `rysteams.feed.death` | %s погибает · %s · %s %s %s | %s dies · %s · %s %s %s |
| `rysteams.feed.death_hidden` | %s погибает · %s | %s dies · %s |
| `rysteams.mark.death` | Гибель %s | %s died here |
| `rysteams.personal.death` | Сообщать о моей смерти | Report my death |
| `rysteams.error.limit` | Достигнут предел для команды | Team limit reached |
| `rysteams.error.too_fast` | Слишком часто, подожди немного | Too fast, wait a moment |
| `rysteams.error.mark_near` | Рядом уже есть метка «%s» | There is already a mark nearby: %s |
| `rysteams.error.merge_busy` | Одна из команд уже участвует в слиянии | One of the teams is already in a merge |
| `rysteams.error.merge_full` | После слияния в команде будет больше %s человек | The merged team would have more than %s members |
| `rysteams.error.merge_notes` | После слияния заметок будет на %s больше предела. Удалите лишние и предложите снова | The merged team would have %s notes over the limit. Remove some and try again |
| `rysteams.error.merge_todos` | После слияния дел будет на %s больше предела. Удалите лишние и предложите снова | The merged team would have %s todos over the limit. Remove some and try again |
| `rysteams.error.merge_marks` | После слияния меток будет на %s больше предела. Удалите лишние и предложите снова | The merged team would have %s marks over the limit. Remove some and try again |
| `rysteams.toast.kicked` | Тебя исключили из команды %s, решение: %s | You were removed from %s by %s |
| `rysteams.toast.disbanded` | Команда %s распущена | Team %s was disbanded |
| `rysteams.toast.disbanded_restore` | Команда %s распущена. Вернуть её можно в течение недели | Team %s was disbanded. You can restore it within a week |
| `key.rysteams.accept` | Принять приглашение | Accept invite |
| `key.rysteams.decline` | Отклонить приглашение | Decline invite |
| `key.rysteams.fresh` | Принять без переноса | Accept, keep separate |
| `rysteams.lobby.pending` | заявка отправлена | request sent |
| `rysteams.lobby.cancel` | Отозвать | Withdraw |
| `rysteams.invite.fresh_key` (изменён) | Без переноса · %s | Keep separate · %s |
| `rysteams.invite.accept` (изменён) | Принять · %s | Accept · %s |
| `rysteams.invite.no` (изменён) | Нет · %s | No · %s |
| `rysteams.card.invite` (изменён) | Пригласить · %s | Invite · %s |

Удалены: `rysteams.help`, `rysteams.header.sub`, `rysteams.online.busy`, `rysteams.invite.sub`, `rysteams.toast.pick_online`, `rysteams.hud.online`, `rysteams.rule.rewards`, `rysteams.rewards.each`, `rysteams.rewards.once`, `rysteams.identity.icon`
