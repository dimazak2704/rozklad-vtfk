-- Ukrainian alphabetical order for catalog lists (Specs.UKRAINIAN): the database collation is en_US.utf8,
-- which sorts by code point and puts Є, І and Ї before А. ICU knows the Ukrainian alphabet:
-- А Б В Г Ґ Д Е Є Ж З И І Ї Й К ... Ь Ю Я. Used in ORDER BY only, the columns keep their collation.
CREATE COLLATION IF NOT EXISTS uk_ua (provider = icu, locale = 'uk-UA');
