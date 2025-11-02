package org.asimon.sefaria.data.demo

import android.content.Context
import android.content.res.AssetManager
import androidx.core.text.isDigitsOnly
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.asimon.sefaria.data.Content
import org.asimon.sefaria.data.Line
import org.asimon.sefaria.data.Schema
import org.asimon.sefaria.data.SchemaInt
import org.asimon.sefaria.data.SchemaString
import org.asimon.sefaria.data.network.NetworkDataSource
import javax.inject.Inject


val tableOfContent = """
  [
    {
    "contents": [
      {
        "contents": [
          {
            "categories": [
              "Tanakh",
              "Torah"
            ],
            "order": 1,
            "primary_category": "Tanakh",
            "enShortDesc": "Creation, the beginning of mankind, and stories of the patriarchs and matriarchs.",
            "heShortDesc": "בריאת העולם, תחילתה של האנושות וסיפורי האבות והאמהות.",
            "corpus": "Tanakh",
            "heTitle": "בראשית",
            "title": "Genesis"
          },
          {
            "categories": [
              "Tanakh",
              "Torah"
            ],
            "order": 2,
            "primary_category": "Tanakh",
            "enShortDesc": "The Israelites’ enslavement in Egypt, miraculous redemption, the giving of the Torah, and building of the Mishkan (Tabernacle).",
            "heShortDesc": "שעבוד בני ישראל במצרים, יציאת מצרים, מתן תורה ובניית המשכן.",
            "corpus": "Tanakh",
            "heTitle": "שמות",
            "title": "Exodus"
          },
          {
            "categories": [
              "Tanakh",
              "Torah"
            ],
            "order": 3,
            "primary_category": "Tanakh",
            "enShortDesc": "Laws of sacrificial worship in the Mishkan (Tabernacle), ritual purity, and other topics like agriculture, ethics, and holidays.",
            "heShortDesc": "עבודת הקורבנות במשכן, דיני טהרה ומצוות אחרות העוסקות בעבודה חקלאית, בהתנהגות חברתית, ובמועדים.",
            "corpus": "Tanakh",
            "heTitle": "ויקרא",
            "title": "Leviticus"
          },
          {
            "categories": [
              "Tanakh",
              "Torah"
            ],
            "order": 4,
            "primary_category": "Tanakh",
            "enShortDesc": "Wanderings of the Israelites in the desert, census, rebellion, spies and war, interspersed with laws.",
            "heShortDesc": "מסעות בני ישראל במדבר, מפקדי אוכלוסין, מרידות, חטא המרגלים, מלחמות ושלל חוקים.",
            "corpus": "Tanakh",
            "heTitle": "במדבר",
            "title": "Numbers"
          },
          {
            "categories": [
              "Tanakh",
              "Torah"
            ],
            "order": 5,
            "primary_category": "Tanakh",
            "enShortDesc": "Moses’ final speeches, recalling events of the desert, reviewing old laws, introducing new ones, and calling for faithfulness to God.",
            "heShortDesc": "נאומי משה האחרונים, חזרה על אירועי המדבר, סקירה של חוקים שנאמרו בספרים הקודמים, הצגת חוקים חדשים וקריאה לנאמנות לאלוהים.",
            "corpus": "Tanakh",
            "heTitle": "דברים",
            "title": "Deuteronomy"
          }
        ],
        "order": 5,
        "enComplete": true,
        "heComplete": true,
        "enDesc": "Five Books of Moses",
        "heDesc": "התורה כוללת את חמשת הספרים הראשונים של התנ\"ך. לפי המסורת הרבנית, התורה שבכתב, יחד עם התורה שבעל פה, המרכיבים יחד את ההלכה היהודית, ניתנו בנבואה למשה. המסורת היהודית מחלקת את התורה לפרשיות, כאשר כל שבוע נקראת פרשה אחת, וקריאת כל חמשת החומשים מסתיימת במהלך כל שנה ושנה. הנוצרים חילקו את התורה לפרקים ופסוקים, וחלוקה זאת נמצאת בשימוש גם אצל יהודים מאז ימי הביניים.",
        "enShortDesc": "The Five Books of Moses",
        "heShortDesc": "",
        "heCategory": "תורה",
        "category": "Torah"
      },
      {
        "contents": [
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 6,
            "primary_category": "Tanakh",
            "enShortDesc": "The Israelites enter, conquer, and settle Israel under the leadership of Joshua.",
            "heShortDesc": "תחת הנהגתו של יהושע, בני ישראל נכנסים לארץ ישראל, כובשים אותה ומתיישבים בה.",
            "corpus": "Tanakh",
            "heTitle": "יהושע",
            "title": "Joshua"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 7,
            "primary_category": "Tanakh",
            "enShortDesc": "Cycles of sin, foreign oppression, repentance, and redemption through leaders appointed by God.",
            "heShortDesc": "מעגלים חוזרים של חטא, דיכוי תחת שלטון זר, חזרה בתשובה וגאולה בעזרת מנהיגים נבחרים.",
            "corpus": "Tanakh",
            "heTitle": "שופטים",
            "title": "Judges"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 8,
            "primary_category": "Tanakh",
            "enShortDesc": "The prophet Samuel, the advent of monarchy with the reign of Saul, and the rise of a young David.",
            "heShortDesc": "הנביא שמואל, מלכות שאול - ראשון המלכים בישראל, ועלייתו לגדולה של דוד הצעיר.",
            "corpus": "Tanakh",
            "heTitle": "שמואל א",
            "title": "I Samuel"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 9,
            "primary_category": "Tanakh",
            "enShortDesc": "King David’s triumphs and challenges as he establishes a united kingdom with Jerusalem as its capital.",
            "heShortDesc": "ניצחונותיו ואתגריו של דוד המלך, כינון הממלכה המאוחדת בישראל וייסוד עיר הבירה ירושלים.",
            "corpus": "Tanakh",
            "heTitle": "שמואל ב",
            "title": "II Samuel"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 10,
            "primary_category": "Tanakh",
            "enShortDesc": "Solomon’s kingship, construction of the Temple, a schism in the kingdom, and Elijah the Prophet.",
            "heShortDesc": "מלכות שלמה, בניית בית המקדש, פילוג הממלכה וסיפורי אליהו הנביא.",
            "corpus": "Tanakh",
            "heTitle": "מלכים א",
            "title": "I Kings"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 11,
            "primary_category": "Tanakh",
            "enShortDesc": "Stories and miracles of the prophet Elisha, the decline of Israel’s kingdoms, and the Temple’s destruction.",
            "heShortDesc": "סיפורי הנביא אלישע וניסיו, דעיכתן של ממלכות ישראל ויהודה וחורבן בית המקדש.",
            "corpus": "Tanakh",
            "heTitle": "מלכים ב",
            "title": "II Kings"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 12,
            "primary_category": "Tanakh",
            "enShortDesc": "Criticism of religious corruption, calls for change, and descriptions of a utopian future.",
            "heShortDesc": "ביקורת על שחיתות דתית וקריאות לשינוי, תיאורים של עתיד אוטופי.",
            "corpus": "Tanakh",
            "heTitle": "ישעיהו",
            "title": "Isaiah"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 13,
            "primary_category": "Tanakh",
            "enShortDesc": "Warnings of Jerusalem’s destruction and demands for repentance, largely rejected by the people, some of whom torture and persecute him.",
            "heShortDesc": "התראות מפני חורבן ירושלים, דרישה לחזרה בתשובה ודחייתה על ידי רוב העם, עינויים ורדיפות שספג ירמיהו בעקבות נבואותיו.",
            "corpus": "Tanakh",
            "heTitle": "ירמיהו",
            "title": "Jeremiah"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 14,
            "primary_category": "Tanakh",
            "enShortDesc": "Dramatic symbolism conveying rebuke or hope, and visions of a future Temple.",
            "heShortDesc": "נבואות תוכחה ונחמה, חזון בית המקדש העתידי.",
            "corpus": "Tanakh",
            "heTitle": "יחזקאל",
            "title": "Ezekiel"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 15,
            "primary_category": "Tanakh",
            "enShortDesc": "Rebuke of Israel for abandoning God, comparing their relationship to that of unfaithful lovers.",
            "heShortDesc": "תוכחה לעם ישראל על שנטש את אלוהיו והשוואת מערכת יחסים זו לבגידה בין בני זוג אוהבים.",
            "corpus": "Tanakh",
            "heTitle": "הושע",
            "title": "Hosea"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 16,
            "primary_category": "Tanakh",
            "enShortDesc": "A locust plague, a call to repent, and a promise of judgement for Israel’s oppressors.",
            "heShortDesc": "נבואה על מכת ארבה גדולה, קריאה לחזרה בתשובה והבטחה לדין עתידי שייעשה לצוררי ישראל.",
            "corpus": "Tanakh",
            "heTitle": "יואל",
            "title": "Joel"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 17,
            "primary_category": "Tanakh",
            "enShortDesc": "Condemnation of oppression and arrogance in the nations and Israel, and a call for reform.",
            "heShortDesc": "הוקעת הדיכוי והיוהרה השוררים באומות ובישראל וקריאה לתיקון מעמיק.",
            "corpus": "Tanakh",
            "heTitle": "עמוס",
            "title": "Amos"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 18,
            "primary_category": "Tanakh",
            "enShortDesc": "The shortest book in Tanakh, at just 21 verses, predicting the downfall of the kingdom of Edom.",
            "heShortDesc": "הספר הקצר ביותר בתנ\"ך, 21 פסוקים בסך הכול, החוזה את נפילת ממלכת אדום.",
            "corpus": "Tanakh",
            "heTitle": "עובדיה",
            "title": "Obadiah"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 19,
            "primary_category": "Tanakh",
            "enShortDesc": "A great fish swallows Jonah when he tries to escape his mission of prophecy, and Jonah repents.",
            "heShortDesc": "דג גדול בולע את יונה כאשר הוא מנסה להימלט משליחותו הנבואית; יונה מתפלל ומגיח החוצה כדי להשלים את משימתו.",
            "corpus": "Tanakh",
            "heTitle": "יונה",
            "title": "Jonah"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 20,
            "primary_category": "Tanakh",
            "enShortDesc": "Berating of Israel and its leadership for insincere ritual worship, and calls for justice and kindness.",
            "heShortDesc": "נזיפה בעם ישראל ובמנהיגיו על צביעותם בעבודת הפולחן וקריאה לצדק ולגמילות חסדים.",
            "corpus": "Tanakh",
            "heTitle": "מיכה",
            "title": "Micah"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 21,
            "primary_category": "Tanakh",
            "enShortDesc": "A celebratory prophecy about the downfall of the Assyrian empire, an oppressor of Israel.",
            "heShortDesc": "נבואה חגיגית על נפילת ממלכת אשור - צוררת ישראל.",
            "corpus": "Tanakh",
            "heTitle": "נחום",
            "title": "Nahum"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 22,
            "primary_category": "Tanakh",
            "enShortDesc": "Charging God to explain the unjust success of the Babylonians, God’s response, and a prayer.",
            "heShortDesc": "קריאה לאלוהים לנימוק ההצלחה חסרת הצדק של ממלכת בבל, תשובתו של אלוהים ותפילה.",
            "corpus": "Tanakh",
            "heTitle": "חבקוק",
            "title": "Habakkuk"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 23,
            "primary_category": "Tanakh",
            "enShortDesc": "Warnings of the destruction God will wreck on the unfaithful and calls for justice and humility.",
            "heShortDesc": "אזהרות מפני החורבן שימיט אלוהים וקריאות לצדק ולענווה.",
            "corpus": "Tanakh",
            "heTitle": "צפניה",
            "title": "Zephaniah"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 24,
            "primary_category": "Tanakh",
            "enShortDesc": "Urgent calls to build the Second Temple and descriptions of its future glory.",
            "heShortDesc": "קריאה בהולה לבניית בית המקדש השני ותיאורים של תפארתו העתידית.",
            "corpus": "Tanakh",
            "heTitle": "חגי",
            "title": "Haggai"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 25,
            "primary_category": "Tanakh",
            "enShortDesc": "Symbolic visions of redemption, explained by angels, and descriptions of the end of days.",
            "heShortDesc": "חזונות סמליים של גאולה, פירושם בידי מלאכים ותיאורי אחרית הימים.",
            "corpus": "Tanakh",
            "heTitle": "זכריה",
            "title": "Zechariah"
          },
          {
            "categories": [
              "Tanakh",
              "Prophets"
            ],
            "order": 26,
            "primary_category": "Tanakh",
            "enShortDesc": "Criticism of disingenuous ritual worship and descriptions of God’s future blessings.",
            "heShortDesc": "ביקורת על השקר והצביעות שליוו את עבודת הפולחן ותיאורים של השפע העתידי שיעניק אלוהים לצדיקים.",
            "corpus": "Tanakh",
            "heTitle": "מלאכי",
            "title": "Malachi"
          }
        ],
        "order": 10,
        "enComplete": true,
        "heComplete": true,
        "enDesc": "",
        "heDesc": "",
        "enShortDesc": "",
        "heShortDesc": "",
        "heCategory": "נביאים",
        "category": "Prophets"
      },
      {
        "contents": [
          {
            "categories": [
              "Tanakh",
              "Writings"
            ],
            "order": 27,
            "primary_category": "Tanakh",
            "enShortDesc": "Poems of despair, hope, gratitude, and supplication to God, attributed to David and others.",
            "heShortDesc": "פיוטים המביעים קשת רגשות - תקווה ודכדוך, שבח ותחנונים לאלוהים - ומיוחסים לדוד המלך ולאישים אחרים.",
            "corpus": "Tanakh",
            "heTitle": "תהילים",
            "title": "Psalms"
          },
          {
            "categories": [
              "Tanakh",
              "Writings"
            ],
            "order": 28,
            "primary_category": "Tanakh",
            "enShortDesc": "Guidance for living a wise, moral, and righteous life, in the form of poems and short statements.",
            "heShortDesc": "מספרי החוכמה, ספר הדרכה לחיי מוסר, חוכמה וצדק שעיקרו פתגמים ואמרי מוסר שיריים.",
            "corpus": "Tanakh",
            "heTitle": "משלי",
            "title": "Proverbs"
          },
          {
            "categories": [
              "Tanakh",
              "Writings"
            ],
            "order": 29,
            "primary_category": "Tanakh",
            "enShortDesc": "Satan convinces God to strike a righteous man with tragedy, spurring conversations about suffering.",
            "heShortDesc": "השטן משכנע את הא-ל להמיט שרשרת אסונות על אדם צדיק, וצערו של איוב מוביל לדיונים על תורת הגמול (צדיק ורע לו).",
            "corpus": "Tanakh",
            "heTitle": "איוב",
            "title": "Job"
          },
          {
            "categories": [
              "Tanakh",
              "Writings"
            ],
            "order": 30,
            "primary_category": "Tanakh",
            "enShortDesc": "Poetic conversations of two lovers, traditionally read as a metaphor for God and Israel.",
            "heShortDesc": "שיח פיוטי בין נאהבים שנתפרש במסורת חז\"ל כאלגוריה ליחסי עם ישראל עם אלוקיו.",
            "corpus": "Tanakh",
            "heTitle": "שיר השירים",
            "title": "Song of Songs"
          },
          {
            "categories": [
              "Tanakh",
              "Writings"
            ],
            "order": 31,
            "primary_category": "Tanakh",
            "enShortDesc": "A Moabite widow remains loyal to her mother-in-law and to Israel, embarking on a new beginning.",
            "heShortDesc": "אלמנה מואבייה נוהגת בחמותה במידת החסד ומצטרפת לעם ישראל בצאתה לדרך חדשה.",
            "corpus": "Tanakh",
            "heTitle": "רות",
            "title": "Ruth"
          },
          {
            "categories": [
              "Tanakh",
              "Writings"
            ],
            "order": 32,
            "primary_category": "Tanakh",
            "enShortDesc": "Laments of Jerusalem’s destruction, grappling with theological explanations.",
            "heShortDesc": "קינה על חורבן ירושלים והתמודדות עם נימוקים תאולוגיים לחורבן.",
            "corpus": "Tanakh",
            "heTitle": "איכה",
            "title": "Lamentations"
          },
          {
            "categories": [
              "Tanakh",
              "Writings"
            ],
            "order": 33,
            "primary_category": "Tanakh",
            "enShortDesc": "An exploration of the meaning of life, reckoning with death, futility, and purpose.",
            "heShortDesc": "חקירת משמעות החיים באמצעות בחינת המוות, הבטלה והתכלית.",
            "corpus": "Tanakh",
            "heTitle": "קהלת",
            "title": "Ecclesiastes"
          },
          {
            "categories": [
              "Tanakh",
              "Writings"
            ],
            "order": 34,
            "primary_category": "Tanakh",
            "enShortDesc": "Esther becomes queen of Persia and foils a plot to destroy the Jews, establishing the Purim holiday.",
            "heShortDesc": "אסתר נעשית מלכת פרס ומסכלת מזימה להשמיד את היהודים, ולרגל נס ההצלה נקבע לדורות חג הפורים.",
            "corpus": "Tanakh",
            "heTitle": "אסתר",
            "title": "Esther"
          },
          {
            "categories": [
              "Tanakh",
              "Writings"
            ],
            "order": 35,
            "primary_category": "Tanakh",
            "enShortDesc": "A Jewish advisor to Babylonian kings interprets dreams and miraculously escapes danger.",
            "heShortDesc": "יועץ יהודי למלכי בבל הפותר חלומות ונמלט מסכנות בדרך נס.",
            "corpus": "Tanakh",
            "heTitle": "דניאל",
            "title": "Daniel"
          },
          {
            "categories": [
              "Tanakh",
              "Writings"
            ],
            "order": 36,
            "primary_category": "Tanakh",
            "enShortDesc": "Rebuilding the Temple after decades of exile and religious revival led by Ezra the scribe.",
            "heShortDesc": "לאחר עשרות שנות גלות בית המקדש נבנה מחדש ועזרא הסופר מוביל תחייה רוחנית.",
            "corpus": "Tanakh",
            "heTitle": "עזרא",
            "title": "Ezra"
          },
          {
            "categories": [
              "Tanakh",
              "Writings"
            ],
            "order": 37,
            "primary_category": "Tanakh",
            "enShortDesc": "Rebuilding Jerusalem’s walls and the nation’s commitment to observe the commandments.",
            "heShortDesc": "שיקום חומות ירושלים ונכונות העם לקבל עליו את שמירת המצוות.",
            "corpus": "Tanakh",
            "heTitle": "נחמיה",
            "title": "Nehemiah"
          },
          {
            "categories": [
              "Tanakh",
              "Writings"
            ],
            "order": 38,
            "primary_category": "Tanakh",
            "enShortDesc": "Recounts of events in the Torah and early Prophets, focusing on King David.",
            "heShortDesc": "חזרה על אירועים שכבר סופרו בתורה ובנביאים ראשונים, בדגש על קורותיו של דוד המלך.",
            "corpus": "Tanakh",
            "heTitle": "דברי הימים א",
            "title": "I Chronicles"
          },
          {
            "categories": [
              "Tanakh",
              "Writings"
            ],
            "order": 39,
            "primary_category": "Tanakh",
            "enShortDesc": "Recounts of events in the Prophets, from Solomon through the First Temple’s destruction.",
            "heShortDesc": "חזרה על אירועים שסופרו בספרי הנביאים מימי שלמה ועד חורבן בית המקדש הראשון.",
            "corpus": "Tanakh",
            "heTitle": "דברי הימים ב",
            "title": "II Chronicles"
          }
        ],
        "order": 15,
        "enComplete": true,
        "heComplete": true,
        "enDesc": "",
        "heDesc": "",
        "enShortDesc": "",
        "heShortDesc": "",
        "heCategory": "כתובים",
        "category": "Writings"
      }
    ],
    "order": 5,
    "enComplete": false,
    "heComplete": false,
    "enDesc": "Tanakh (the Hebrew Bible) is Judaism’s foundational text. The word “Tanakh” is an acronym of its three parts: Torah (The Five Books of Moses), Nevi’im (Prophets), and Ketuvim (Writings). It contains stories, law, poetry, and teachings about God and humanity.",
    "heDesc": "התנ\"ך (תורה, נביאים, כתובים) הוא טקסט היסוד של היהדות. ב-24 ספריו נכללים סיפורים, חוקים, שירה וכן תובנות ולקחים הקשורים לאלוהים, לעם ישראל ולאנושות.",
    "enShortDesc": "Torah, Prophets, and Writings, which together make up the Hebrew Bible, Judaism's foundational text.",
    "heShortDesc": "תורה, נביאים וכתובים - שלושת החלקים המרכיבים את התורה שבכתב, טקסט היסוד של היהדות.",
    "heCategory": "תנ\"ך",
    "category": "Tanakh"
  }
]
"""
val text = """Genesis.1.1@<big>בְּ</big>רֵאשִׁ֖ית בָּרָ֣א אֱלֹהִ֑ים אֵ֥ת הַשָּׁמַ֖יִם וְאֵ֥ת הָאָֽרֶץ׃
Genesis.1.2@וְהָאָ֗רֶץ הָיְתָ֥ה תֹ֙הוּ֙ וָבֹ֔הוּ וְחֹ֖שֶׁךְ עַל־פְּנֵ֣י תְה֑וֹם וְר֣וּחַ אֱלֹהִ֔ים מְרַחֶ֖פֶת עַל־פְּנֵ֥י הַמָּֽיִם׃
Genesis.1.3@וַיֹּ֥אמֶר אֱלֹהִ֖ים יְהִ֣י א֑וֹר וַֽיְהִי־אֽוֹר׃
Genesis.1.4@וַיַּ֧רְא אֱלֹהִ֛ים אֶת־הָא֖וֹר כִּי־ט֑וֹב וַיַּבְדֵּ֣ל אֱלֹהִ֔ים בֵּ֥ין הָא֖וֹר וּבֵ֥ין הַחֹֽשֶׁךְ׃
Genesis.1.5@וַיִּקְרָ֨א אֱלֹהִ֤ים&thinsp;<small>׀</small>&thinsp;לָאוֹר֙ י֔וֹם וְלַחֹ֖שֶׁךְ קָ֣רָא לָ֑יְלָה וַֽיְהִי־עֶ֥רֶב וַֽיְהִי־בֹ֖קֶר י֥וֹם אֶחָֽד׃&nbsp;<span class="mam-spi-pe">{פ}</span><br>
Genesis.1.6@וַיֹּ֣אמֶר אֱלֹהִ֔ים יְהִ֥י רָקִ֖יעַ בְּת֣וֹךְ הַמָּ֑יִם וִיהִ֣י מַבְדִּ֔יל בֵּ֥ין מַ֖יִם לָמָֽיִם׃
Genesis.1.7@וַיַּ֣עַשׂ אֱלֹהִים֮ אֶת־הָרָקִ֒יעַ֒ וַיַּבְדֵּ֗ל בֵּ֤ין הַמַּ֙יִם֙ אֲשֶׁר֙ מִתַּ֣חַת לָרָקִ֔יעַ וּבֵ֣ין הַמַּ֔יִם אֲשֶׁ֖ר מֵעַ֣ל לָרָקִ֑יעַ וַֽיְהִי־כֵֽן׃
Genesis.1.8@וַיִּקְרָ֧א אֱלֹהִ֛ים לָֽרָקִ֖יעַ שָׁמָ֑יִם וַֽיְהִי־עֶ֥רֶב וַֽיְהִי־בֹ֖קֶר י֥וֹם שֵׁנִֽי׃&nbsp;<span class="mam-spi-pe">{פ}</span><br>
Genesis.1.9@וַיֹּ֣אמֶר אֱלֹהִ֗ים יִקָּו֨וּ הַמַּ֜יִם מִתַּ֤חַת הַשָּׁמַ֙יִם֙ אֶל־מָק֣וֹם אֶחָ֔ד וְתֵרָאֶ֖ה הַיַּבָּשָׁ֑ה וַֽיְהִי־כֵֽן׃
Genesis.1.10@וַיִּקְרָ֨א אֱלֹהִ֤ים&thinsp;<small>׀</small>&thinsp;לַיַּבָּשָׁה֙ אֶ֔רֶץ וּלְמִקְוֵ֥ה הַמַּ֖יִם קָרָ֣א יַמִּ֑ים וַיַּ֥רְא אֱלֹהִ֖ים כִּי־טֽוֹב׃
Genesis.1.11@וַיֹּ֣אמֶר אֱלֹהִ֗ים תַּֽדְשֵׁ֤א הָאָ֙רֶץ֙ דֶּ֗שֶׁא עֵ֚שֶׂב מַזְרִ֣יעַ זֶ֔רַע עֵ֣ץ פְּרִ֞י עֹ֤שֶׂה פְּרִי֙ לְמִינ֔וֹ אֲשֶׁ֥ר זַרְעוֹ־ב֖וֹ עַל־הָאָ֑רֶץ וַֽיְהִי־כֵֽן׃
Genesis.1.12@וַתּוֹצֵ֨א הָאָ֜רֶץ דֶּ֠שֶׁא עֵ֣שֶׂב מַזְרִ֤יעַ זֶ֙רַע֙ לְמִינֵ֔הוּ וְעֵ֧ץ עֹֽשֶׂה־פְּרִ֛י אֲשֶׁ֥ר זַרְעוֹ־ב֖וֹ לְמִינֵ֑הוּ וַיַּ֥רְא אֱלֹהִ֖ים כִּי־טֽוֹב׃
Genesis.1.13@וַֽיְהִי־עֶ֥רֶב וַֽיְהִי־בֹ֖קֶר י֥וֹם שְׁלִישִֽׁי׃&nbsp;<span class="mam-spi-pe">{פ}</span><br>
Genesis.1.14@וַיֹּ֣אמֶר אֱלֹהִ֗ים יְהִ֤י מְאֹרֹת֙ בִּרְקִ֣יעַ הַשָּׁמַ֔יִם לְהַבְדִּ֕יל בֵּ֥ין הַיּ֖וֹם וּבֵ֣ין הַלָּ֑יְלָה וְהָי֤וּ לְאֹתֹת֙ וּלְמ֣וֹעֲדִ֔ים וּלְיָמִ֖ים וְשָׁנִֽים׃
Genesis.1.15@וְהָי֤וּ לִמְאוֹרֹת֙ בִּרְקִ֣יעַ הַשָּׁמַ֔יִם לְהָאִ֖יר עַל־הָאָ֑רֶץ וַֽיְהִי־כֵֽן׃
Genesis.1.16@וַיַּ֣עַשׂ אֱלֹהִ֔ים אֶת־שְׁנֵ֥י הַמְּאֹרֹ֖ת הַגְּדֹלִ֑ים אֶת־הַמָּא֤וֹר הַגָּדֹל֙ לְמֶמְשֶׁ֣לֶת הַיּ֔וֹם וְאֶת־הַמָּא֤וֹר הַקָּטֹן֙ לְמֶמְשֶׁ֣לֶת הַלַּ֔יְלָה וְאֵ֖ת הַכּוֹכָבִֽים׃
Genesis.1.17@וַיִּתֵּ֥ן אֹתָ֛ם אֱלֹהִ֖ים בִּרְקִ֣יעַ הַשָּׁמָ֑יִם לְהָאִ֖יר עַל־הָאָֽרֶץ׃
Genesis.1.18@וְלִמְשֹׁל֙ בַּיּ֣וֹם וּבַלַּ֔יְלָה וּֽלְהַבְדִּ֔יל בֵּ֥ין הָא֖וֹר וּבֵ֣ין הַחֹ֑שֶׁךְ וַיַּ֥רְא אֱלֹהִ֖ים כִּי־טֽוֹב׃
Genesis.1.19@וַֽיְהִי־עֶ֥רֶב וַֽיְהִי־בֹ֖קֶר י֥וֹם רְבִיעִֽי׃&nbsp;<span class="mam-spi-pe">{פ}</span><br>
Genesis.1.20@וַיֹּ֣אמֶר אֱלֹהִ֔ים יִשְׁרְצ֣וּ הַמַּ֔יִם שֶׁ֖רֶץ נֶ֣פֶשׁ חַיָּ֑ה וְעוֹף֙ יְעוֹפֵ֣ף עַל־הָאָ֔רֶץ עַל־פְּנֵ֖י רְקִ֥יעַ הַשָּׁמָֽיִם׃
Genesis.1.21@וַיִּבְרָ֣א אֱלֹהִ֔ים אֶת־הַתַּנִּינִ֖ם הַגְּדֹלִ֑ים וְאֵ֣ת כׇּל־נֶ֣פֶשׁ הַֽחַיָּ֣ה&thinsp;<small>׀</small>&thinsp;הָֽרֹמֶ֡שֶׂת אֲשֶׁר֩ שָׁרְצ֨וּ הַמַּ֜יִם לְמִֽינֵהֶ֗ם וְאֵ֨ת כׇּל־ע֤וֹף כָּנָף֙ לְמִינֵ֔הוּ וַיַּ֥רְא אֱלֹהִ֖ים כִּי־טֽוֹב׃
Genesis.1.22@וַיְבָ֧רֶךְ אֹתָ֛ם אֱלֹהִ֖ים לֵאמֹ֑ר פְּר֣וּ וּרְב֗וּ וּמִלְא֤וּ אֶת־הַמַּ֙יִם֙ בַּיַּמִּ֔ים וְהָע֖וֹף יִ֥רֶב בָּאָֽרֶץ׃
Genesis.1.23@וַֽיְהִי־עֶ֥רֶב וַֽיְהִי־בֹ֖קֶר י֥וֹם חֲמִישִֽׁי׃&nbsp;<span class="mam-spi-pe">{פ}</span><br>
Genesis.1.24@וַיֹּ֣אמֶר אֱלֹהִ֗ים תּוֹצֵ֨א הָאָ֜רֶץ נֶ֤פֶשׁ חַיָּה֙ לְמִינָ֔הּ בְּהֵמָ֥ה וָרֶ֛מֶשׂ וְחַֽיְתוֹ־אֶ֖רֶץ לְמִינָ֑הּ וַֽיְהִי־כֵֽן׃
Genesis.1.25@וַיַּ֣עַשׂ אֱלֹהִים֩ אֶת־חַיַּ֨ת הָאָ֜רֶץ לְמִינָ֗הּ וְאֶת־הַבְּהֵמָה֙ לְמִינָ֔הּ וְאֵ֛ת כׇּל־רֶ֥מֶשׂ הָֽאֲדָמָ֖ה לְמִינֵ֑הוּ וַיַּ֥רְא אֱלֹהִ֖ים כִּי־טֽוֹב׃
Genesis.1.26@וַיֹּ֣אמֶר אֱלֹהִ֔ים נַֽעֲשֶׂ֥ה אָדָ֛ם בְּצַלְמֵ֖נוּ כִּדְמוּתֵ֑נוּ וְיִרְדּוּ֩ בִדְגַ֨ת הַיָּ֜ם וּבְע֣וֹף הַשָּׁמַ֗יִם וּבַבְּהֵמָה֙ וּבְכׇל־הָאָ֔רֶץ וּבְכׇל־הָרֶ֖מֶשׂ הָֽרֹמֵ֥שׂ עַל־הָאָֽרֶץ׃
Genesis.1.27@וַיִּבְרָ֨א אֱלֹהִ֤ים&thinsp;<small>׀</small>&thinsp;אֶת־הָֽאָדָם֙ בְּצַלְמ֔וֹ בְּצֶ֥לֶם אֱלֹהִ֖ים בָּרָ֣א אֹת֑וֹ זָכָ֥ר וּנְקֵבָ֖ה בָּרָ֥א אֹתָֽם׃
Genesis.1.28@וַיְבָ֣רֶךְ אֹתָם֮ אֱלֹהִים֒ וַיֹּ֨אמֶר לָהֶ֜ם אֱלֹהִ֗ים פְּר֥וּ וּרְב֛וּ וּמִלְא֥וּ אֶת־הָאָ֖רֶץ וְכִבְשֻׁ֑הָ וּרְד֞וּ בִּדְגַ֤ת הַיָּם֙ וּבְע֣וֹף הַשָּׁמַ֔יִם וּבְכׇל־חַיָּ֖ה הָֽרֹמֶ֥שֶׂת עַל־הָאָֽרֶץ׃
Genesis.1.29@וַיֹּ֣אמֶר אֱלֹהִ֗ים הִנֵּה֩ נָתַ֨תִּי לָכֶ֜ם אֶת־כׇּל־עֵ֣שֶׂב&thinsp;<b>׀</b> זֹרֵ֣עַ זֶ֗רַע אֲשֶׁר֙ עַל־פְּנֵ֣י כׇל־הָאָ֔רֶץ וְאֶת־כׇּל־הָעֵ֛ץ אֲשֶׁר־בּ֥וֹ פְרִי־עֵ֖ץ זֹרֵ֣עַ זָ֑רַע לָכֶ֥ם יִֽהְיֶ֖ה לְאׇכְלָֽה׃
Genesis.1.30@וּֽלְכׇל־חַיַּ֣ת הָ֠אָ֠רֶץ וּלְכׇל־ע֨וֹף הַשָּׁמַ֜יִם וּלְכֹ֣ל&thinsp;<b>׀</b> רוֹמֵ֣שׂ עַל־הָאָ֗רֶץ אֲשֶׁר־בּוֹ֙ נֶ֣פֶשׁ חַיָּ֔ה אֶת־כׇּל־יֶ֥רֶק עֵ֖שֶׂב לְאׇכְלָ֑ה וַֽיְהִי־כֵֽן׃
Genesis.1.31@וַיַּ֤רְא אֱלֹהִים֙ אֶת־כׇּל־אֲשֶׁ֣ר עָשָׂ֔ה וְהִנֵּה־ט֖וֹב מְאֹ֑ד וַֽיְהִי־עֶ֥רֶב וַֽיְהִי־בֹ֖קֶר י֥וֹם הַשִּׁשִּֽׁי׃&nbsp;<span class="mam-spi-pe">{פ}</span><br>"""
val schema = """Genesis.1@31@1,8242
Genesis.2@25@8243,14317
Genesis.3@24@14318,21066
Genesis.4@26@21067,27064
Genesis.5@32@27065,34713
Genesis.6@22@34714,40313
Genesis.7@24@40314,46378
Genesis.8@22@46379,52114
Genesis.9@29@52115,58659
Genesis.10@32@58660,64316
Genesis.11@32@64317,72104
Genesis.12@20@72105,77104
Genesis.13@18@77105,81447
Genesis.14@24@81448,87939
Genesis.15@21@87940,92678
Genesis.16@16@92679,96721
Genesis.17@27@96722,103522
Genesis.18@33@103523,111826
Genesis.19@38@111827,122173
Genesis.20@18@122174,127217
Genesis.21@34@127218,135232
Genesis.22@24@135233,141979
Genesis.23@20@141980,146980
Genesis.24@67@146981,164045
Genesis.25@34@164046,171786
Genesis.26@35@171787,180925
Genesis.27@46@180926,192665
Genesis.28@22@192666,198645
Genesis.29@35@198646,206855
Genesis.30@43@206856,217213
Genesis.31@54@217214,230822
Genesis.32@33@230823,239131
Genesis.33@20@239132,244142
Genesis.34@31@244143,251775
Genesis.35@29@251776,258826
Genesis.36@43@258827,268155
Genesis.37@36@268156,277489
Genesis.38@30@277490,284921
Genesis.39@23@284922,291294
Genesis.40@23@291295,297104
Genesis.41@57@297105,311511
Genesis.42@38@311512,321217
Genesis.43@34@321218,330647
Genesis.44@34@330648,338953
Genesis.45@28@338954,346073
Genesis.46@34@346074,354148
Genesis.47@31@354149,363493
Genesis.48@22@363494,369896
Genesis.49@33@369897,377504
Genesis.50@26@377505,384219
Genesis@50@1,384219"""

class MockNetworkDataSource @Inject constructor(@param:ApplicationContext private val context: Context,) : NetworkDataSource {

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun getTableOfContent(): List<Content> {
        return Json.decodeFromStream(context.getAssets().open("tableofcontent.json"))
    }

    override suspend fun getText(ref: String): List<Line> {
        return text.lines().map { line ->
            val splited = line.split('@')
            Line(splited[0], splited[1])
        }
    }

    override suspend fun getSchema(ref: String): List<Schema> {
        return schema.lines().map { line ->
            val (ref, subRefs) = line.split('@')
            if (subRefs.isDigitsOnly()) {
                SchemaInt(ref, subRefs.toInt())
            } else {
                SchemaString(ref, subRefs
                    .trim('[', ']')
                    .split(',')
                    .map { it -> it.trim(' ', '\'')})
            }
        }
    }

}