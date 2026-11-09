# Сторонний код в составе RYScope

## Just Enough Items (JEI)

- Автор: mezz. Репозиторий: https://github.com/mezz/JustEnoughItems
- Лицензия: MIT. Текст: `neoforge/src/main/resources/META-INF/licenses/JEI-LICENSE.txt`, в jar мода лежит по пути `META-INF/licenses/JEI-LICENSE.txt`.
- Как используется: неизменённый jar JEI вложен в jar RYScope (`META-INF/jarjar/`). Версия задаётся свойством `jei_embed_version` в `gradle.properties`.
- Зачем: плагины модов написаны под API JEI. Вложенный JEI загружает их, а RYScope берёт у него категории, рецепты и ингредиенты. Интерфейс JEI при этом отключён его же штатным API.

Если в папке mods есть свой JEI, загрузчик NeoForge берёт его, даже когда он старее вложенного. Минимальная версия, с которой работает мод: 19.42.0.379.
