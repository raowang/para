/*
 * Copyright 2013-2017 Erudika. https://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.i18n;

import com.erudika.para.persistence.DAO;
import com.erudika.para.search.Search;
import com.erudika.para.core.Translation;
import com.erudika.para.core.Sysprop;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.Pager;
import com.erudika.para.utils.Utils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.lang3.LocaleUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;

/**
 * Utility class for language operations.
 * These can be used to build a crowdsourced translation system.
 * @author Alex Bogdanovski [alex@erudika.com]
 * @see Translation
 */
@Singleton
public class LanguageUtils {

	private static final Logger logger = LoggerFactory.getLogger(LanguageUtils.class);

	private static final HashMap<String, Locale> ALL_LOCALES = new HashMap<String, Locale>();
	static {
		for (Locale loc : LocaleUtils.availableLocaleList()) {
			String locstr = loc.getLanguage();
			if (!StringUtils.isBlank(locstr)) {
				ALL_LOCALES.put(locstr, loc);
			}
		}
	}

	private static final Map<String, Map<String, String>> LANG_CACHE =
			new ConcurrentHashMap<String, Map<String, String>>(ALL_LOCALES.size());

	private String deflangCode;
	private final String keyPrefix = "language".concat(Config.SEPARATOR);
	private final String progressKey = keyPrefix.concat("progress");

	private static final int PLUS = -1;
	private static final int MINUS = -2;

	private final Search search;
	private final DAO dao;


	/**
	 * Default constructor.
	 * @param search a core search instance
	 * @param dao a core persistence instance
	 */
	@Inject
	public LanguageUtils(Search search, DAO dao) {
		this.search = search;
		this.dao = dao;
	}

	/**
	 * Returns a map of all translations for a given language.
	 * Defaults to the default language which must be set.
	 * @param appid appid name of the {@link com.erudika.para.core.App}
	 * @param langCode the 2-letter language code
	 * @return the language map
	 */
	public Map<String, String> readLanguage(String appid, String langCode) {
		if (StringUtils.isBlank(langCode) || !ALL_LOCALES.containsKey(langCode) ||
				langCode.equals(getDefaultLanguageCode())) {
			return getDefaultLanguage(appid);
		} else if (LANG_CACHE.containsKey(langCode)) {
			return LANG_CACHE.get(langCode);
		}

		// load language map from file
		Map<String, String> lang = readLanguageFromFile(appid, langCode);
		if (lang == null || lang.isEmpty()) {
			// or try to load from DB
			Sysprop s = dao.read(appid, keyPrefix.concat(langCode));
			if (s != null && !s.getProperties().isEmpty()) {
				Map<String, Object> loaded = s.getProperties();
				lang = new TreeMap<>();
				for (Map.Entry<String, Object> entry : loaded.entrySet()) {
					lang.put(entry.getKey(), String.valueOf(entry.getValue()));
				}
			}
		}
		if (lang == null || lang.isEmpty()) {
			return getDefaultLanguage(appid);
		} else {
			LANG_CACHE.put(langCode, lang);
			return Collections.unmodifiableMap(lang);
		}
	}

	/**
	 * Persists the language map in the data store. Overwrites any existing maps.
	 * @param appid appid name of the {@link com.erudika.para.core.App}
	 * @param langCode the 2-letter language code
	 * @param lang the language map
	 * @param writeToDatabase true if you want the language map to be stored in the DB as well
	 */
	public void writeLanguage(String appid, String langCode, Map<String, String> lang, boolean writeToDatabase) {
		if (lang == null || lang.isEmpty() || StringUtils.isBlank(langCode) || !ALL_LOCALES.containsKey(langCode)) {
			return;
		}
		writeLanguageToFile(appid, langCode, lang);

		if (writeToDatabase) {
			// this will overwrite a saved language map!
			Sysprop s = new Sysprop(keyPrefix.concat(langCode));
			Map<String, String> dlang = getDefaultLanguage(appid);
			for (Map.Entry<String, String> entry : dlang.entrySet()) {
				String key = entry.getKey();
				if (lang.containsKey(key)) {
					s.addProperty(key, lang.get(key));
				} else {
					s.addProperty(key, entry.getValue());
				}
			}
			dao.create(appid, s);
		}
	}

	/**
	 * Returns a non-null locale for a given language code.
	 * @param langCode the 2-letter language code
	 * @return a locale. default is English
	 */
	public Locale getProperLocale(String langCode) {
		langCode = StringUtils.substring(langCode, 0, 2);
		langCode = (StringUtils.isBlank(langCode) || !ALL_LOCALES.containsKey(langCode)) ?
				"en" : langCode.trim().toLowerCase();
		return ALL_LOCALES.get(langCode);
	}

	/**
	 * Returns the default language map.
	 * @param appid appid name of the {@link com.erudika.para.core.App}
	 * @return the default language map or an empty map if the default isn't set.
	 */
	public Map<String, String> getDefaultLanguage(String appid) {
		if (!LANG_CACHE.containsKey(getDefaultLanguageCode())) {
			logger.info("Default language map not set, loading English.");
			Map<String, String> deflang = readLanguageFromFile(appid, getDefaultLanguageCode());
			if (deflang != null && !deflang.isEmpty()) {
				LANG_CACHE.put(getDefaultLanguageCode(), deflang);
				return Collections.unmodifiableMap(deflang);
			}
		}
		return Collections.unmodifiableMap(LANG_CACHE.get(getDefaultLanguageCode()));
	}

	/**
	 * Sets the default language map. It is the basis language template which is to be translated.
	 * @param deflang the default language map
	 */
	public void setDefaultLanguage(Map<String, String> deflang) {
		if (deflang != null && !deflang.isEmpty()) {
			LANG_CACHE.put(getDefaultLanguageCode(), deflang);
		}
	}

	/**
	 * Returns the default language code.
	 * @return the 2-letter language code
	 */
	public String getDefaultLanguageCode() {
		if (deflangCode == null) {
			deflangCode = "en";
		}
		return deflangCode;
	}

	/**
	 * Sets the default language code.
	 * @param langCode the 2-letter language code
	 */
	public void setDefaultLanguageCode(String langCode) {
		this.deflangCode = langCode;
	}

	/**
	 * Returns a list of translations for a specific string.
	 * @param appid appid name of the {@link com.erudika.para.core.App}
	 * @param locale a locale
	 * @param key the string key
	 * @param pager the pager object
	 * @return a list of translations
	 */
	public List<Translation> readAllTranslationsForKey(String appid, String locale, String key, Pager pager) {
		Map<String, Object> terms = new HashMap<>(2);
		terms.put("thekey", key);
		terms.put("locale", locale);
		return search.findTerms(appid, Utils.type(Translation.class), terms, true, pager);
	}

	/**
	 * Returns the set of all approved translations.
	 * @param appid appid name of the {@link com.erudika.para.core.App}
	 * @param langCode the 2-letter language code
	 * @return a set of keys for approved translations
	 */
	public Set<String> getApprovedTransKeys(String appid, String langCode) {
		HashSet<String> approvedTransKeys = new HashSet<>();
		if (StringUtils.isBlank(langCode)) {
			return approvedTransKeys;
		}

		for (Map.Entry<String, String> entry : readLanguage(appid, langCode).entrySet()) {
			if (!getDefaultLanguage(appid).get(entry.getKey()).equals(entry.getValue())) {
				approvedTransKeys.add(entry.getKey());
			}
		}
		return approvedTransKeys;
	}

	/**
	 * Returns a map of language codes and the percentage of translated string for that language.
	 * @param appid appid name of the {@link com.erudika.para.core.App}
	 * @return a map indicating translation progress
	 */
	public Map<String, Integer> getTranslationProgressMap(String appid) {
		if (dao == null) {
			return Collections.emptyMap();
		}
		Sysprop progress = dao.read(appid, progressKey);
		Map<String, Integer> progressMap = new HashMap<>(ALL_LOCALES.size());
		boolean isMissing = progress == null;
		if (isMissing) {
			progress = new Sysprop(progressKey);
			progress.addProperty(getDefaultLanguageCode(), 100);
		}
		for (String langCode : ALL_LOCALES.keySet()) {
			Object percent = progress.getProperties().get(langCode);
			if (percent != null && percent instanceof Number) {
				progressMap.put(langCode, (Integer) percent);
			} else {
				progressMap.put(langCode, 0);
			}
		}
		if (isMissing) {
			dao.create(appid, progress);
		}
		return progressMap;
	}

	/**
	 * Returns a map of all language codes and their locales.
	 * @return a map of language codes to locales
	 */
	public Map<String, Locale> getAllLocales() {
		return ALL_LOCALES;
	}

	/**
	 * Approves a translation for a given language.
	 * @param appid appid name of the {@link com.erudika.para.core.App}
	 * @param langCode the 2-letter language code
	 * @param key the translation key
	 * @param value the translated string
	 * @return true if the operation was successful
	 */
	public boolean approveTranslation(String appid, String langCode, String key, String value) {
		if (langCode == null || key == null || value == null || getDefaultLanguageCode().equals(langCode)) {
			return false;
		}
		Sysprop s = dao.read(appid, keyPrefix.concat(langCode));

		if (s != null && !value.equals(s.getProperty(key))) {
			s.addProperty(key, value);
			dao.update(appid, s);
			updateTranslationProgressMap(appid, langCode, PLUS);
			return true;
		}
		return false;
	}

	/**
	 * Disapproves a translation for a given language.
	 * @param appid appid name of the {@link com.erudika.para.core.App}
	 * @param langCode the 2-letter language code
	 * @param key the translation key
	 * @return true if the operation was successful
	 */
	public boolean disapproveTranslation(String appid, String langCode, String key) {
		if (langCode == null || key == null || getDefaultLanguageCode().equals(langCode)) {
			return false;
		}
		Sysprop s = dao.read(appid, keyPrefix.concat(langCode));
		String defStr = getDefaultLanguage(appid).get(key);

		if (s != null && !defStr.equals(s.getProperty(key))) {
			s.addProperty(key, defStr);
			dao.update(appid, s);
			updateTranslationProgressMap(appid, langCode, MINUS);
			return true;
		}
		return false;
	}

	/**
	 * Updates the progress for all languages.
	 * @param appid appid name of the {@link com.erudika.para.core.App}
	 * @param langCode the 2-letter language code
	 * @param value {@link #PLUS}, {@link #MINUS} or the total percent of completion (0-100)
	 */
	private void updateTranslationProgressMap(String appid, String langCode, int value) {
		if (dao == null || getDefaultLanguageCode().equals(langCode)) {
			return;
		}

		double defsize = getDefaultLanguage(appid).size();
		double approved = value;

		Map<String, Integer> progress = getTranslationProgressMap(appid);
		Integer percent = progress.get(langCode);
		if (value == PLUS) {
			approved = Math.round(percent * (defsize / 100) + 1);
		} else if (value == MINUS) {
			approved = Math.round(percent * (defsize / 100) - 1);
		}
		int allowedUntranslated = defsize > 10 ? 3 : 0;
		// allow 3 identical words per language (i.e. Email, etc)
		if (approved >= defsize - allowedUntranslated) {
			approved = defsize;
		}

		if (((int) defsize) == 0) {
			progress.put(langCode, 0);
		} else {
			progress.put(langCode, (int) ((approved / defsize) * 100));
		}
		if (percent < 100 && !percent.equals(progress.get(langCode))) {
			Sysprop s = new Sysprop(progressKey);
			for (Map.Entry<String, Integer> entry : progress.entrySet()) {
				s.addProperty(entry.getKey(), entry.getValue());
			}
			dao.create(appid, s);
		}
	}

	private Map<String, String> readLanguageFromFile(String appid, String langCode) {
		Map<String, String> langmap = new TreeMap<>();
		if (langCode == null || langCode.length() != 2) {
			return langmap;
		}
		Properties lang = new Properties();
		String file = "lang_" + langCode + ".properties";
		InputStream ins = null;
		try {
			ins = LanguageUtils.class.getClassLoader().getResourceAsStream(file);
			if (ins == null) {
				return langmap;
			}
			int progress = 0;
			lang.load(ins);
			for (String propKey : lang.stringPropertyNames()) {
				String propVal = lang.getProperty(propKey);
				if (!langCode.equals(getDefaultLanguageCode()) && !StringUtils.isBlank(propVal) &&
						!StringUtils.equalsIgnoreCase(propVal, getDefaultLanguage(appid).get(propKey))) {
					progress++;
				}
				langmap.put(propKey, propVal);
			}
			if (langCode.equals(getDefaultLanguageCode())) {
				progress = langmap.size(); // 100%
			}
			if (progress > 0) {
				updateTranslationProgressMap(appid, langCode, progress);
			}
		} catch (Exception e) {
			logger.info("Could not read language file " + file + ": {}", e.toString());
		} finally {
			try {
				if (ins != null) {
					ins.close();
				}
			} catch (IOException ex) {
				logger.error(null, ex);
			}
		}
		return langmap;
	}

	private void writeLanguageToFile(String appid, String langCode, Map<String, String> lang) {
		if (lang == null || lang.isEmpty() || langCode == null || langCode.length() != 2) {
			return;
		}
		FileOutputStream fos = null;
		try {
			Properties langProps = new Properties();
			langProps.putAll(lang);
			File file = new File("lang_" + langCode + ".properties");
			fos = new FileOutputStream(file);
			langProps.store(fos, langCode);

			int progress = 0;
			for (Map.Entry<String, String> entry : lang.entrySet()) {
				if (!getDefaultLanguage(appid).get(entry.getKey()).equals(entry.getValue())) {
					progress++;
				}
			}
			if (progress > 0) {
				updateTranslationProgressMap(appid, langCode, progress);
			}
		} catch (Exception ex) {
			logger.error("Could not write language to file: {}", ex.toString());
		} finally {
			try {
				if (fos != null) {
					fos.close();
				}
			} catch (IOException ex) {
				logger.error(null, ex);
			}
		}
	}
}
