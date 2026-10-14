// Borrower-language labels for the sanction letter and Key Fact Statement (RBI: KFS in a language the borrower
// understands). English is always printed alongside. Translations must be checked by the lender's compliance team.
export const LANGS: Record<string, string> = {
  en: 'English', hi: 'हिन्दी', mr: 'मराठी', ta: 'தமிழ்', ml: 'മലയാളം', kn: 'ಕನ್ನಡ', te: 'తెలుగు', bn: 'বাংলা', gu: 'ગુજરાતી',
}

type Dict = Record<string, string>

const en: Dict = {
  letter: 'Sanction letter', kfs: 'Key Fact Statement', dear: 'Dear',
  intro: 'We are pleased to sanction your loan on the terms in this letter and the Key Fact Statement below.',
  appNo: 'Application number', product: 'Product', amount: 'Sanctioned amount', fee: 'Processing fee', gst: 'GST on processing fee',
  insurance: 'Insurance', net: 'Net amount disbursed', rate: 'Interest rate (per year, reducing balance)', apr: 'Annual percentage rate (APR, all charges included)',
  tenure: 'Tenure', months: 'months', emi: 'Instalment (EMI)', count: 'Number of instalments', interest: 'Total interest', payable: 'Total amount payable',
  repayment: 'Repayment method', cooling: 'Cooling-off (look-up) period', coolingText: 'days: you may exit the loan by repaying the principal and the proportionate APR, with no penalty',
  contingent: 'Charges that apply only in certain events', foreclosure: 'Foreclosure charge', bounce: 'Bounce charge', penal: 'Penal charge',
  conditions: 'Conditions of sanction', grievance: 'Grievance redressal', validity: 'This sanction is valid for 30 days from the date of this letter.',
  by: 'Sanctioned by', date: 'Date', schedule: 'Repayment schedule', month: 'Month', principal: 'Principal', balance: 'Balance',
  acceptance: 'Borrower’s acceptance', accepted: 'Accepted by OTP on', notAccepted: 'Not yet accepted. The borrower accepts with the OTP sent to the registered mobile number.',
  recovery: 'Recovery agents carry the lender’s authorisation letter and identity card and do not call before 8 a.m. or after 7 p.m.',
  english: 'If the translation and the English text differ, the English text applies.',
}

const hi: Dict = {
  letter: 'स्वीकृति पत्र', kfs: 'मुख्य तथ्य विवरण', dear: 'प्रिय',
  intro: 'हमें इस पत्र और नीचे दिए गए मुख्य तथ्य विवरण की शर्तों पर आपका ऋण स्वीकृत करते हुए प्रसन्नता है।',
  appNo: 'आवेदन संख्या', product: 'उत्पाद', amount: 'स्वीकृत राशि', fee: 'प्रोसेसिंग शुल्क', gst: 'प्रोसेसिंग शुल्क पर जीएसटी',
  insurance: 'बीमा', net: 'वितरित शुद्ध राशि', rate: 'ब्याज दर (प्रति वर्ष, घटती शेष राशि पर)', apr: 'वार्षिक प्रतिशत दर (एपीआर, सभी शुल्क सहित)',
  tenure: 'अवधि', months: 'महीने', emi: 'किस्त (ईएमआई)', count: 'किस्तों की संख्या', interest: 'कुल ब्याज', payable: 'कुल देय राशि',
  repayment: 'भुगतान का तरीका', cooling: 'कूलिंग-ऑफ (लुक-अप) अवधि', coolingText: 'दिन: आप मूलधन और आनुपातिक एपीआर चुकाकर बिना किसी दंड के ऋण से बाहर निकल सकते हैं',
  contingent: 'कुछ स्थितियों में ही लगने वाले शुल्क', foreclosure: 'पूर्व-भुगतान (फोरक्लोज़र) शुल्क', bounce: 'बाउंस शुल्क', penal: 'दंडात्मक शुल्क',
  conditions: 'स्वीकृति की शर्तें', grievance: 'शिकायत निवारण', validity: 'यह स्वीकृति इस पत्र की तारीख से 30 दिनों तक मान्य है।',
  by: 'स्वीकृतकर्ता', date: 'तारीख', schedule: 'भुगतान अनुसूची', month: 'महीना', principal: 'मूलधन', balance: 'शेष राशि',
  acceptance: 'उधारकर्ता की स्वीकृति', accepted: 'ओटीपी द्वारा स्वीकार किया गया', notAccepted: 'अभी स्वीकार नहीं किया गया। उधारकर्ता पंजीकृत मोबाइल पर भेजे गए ओटीपी से स्वीकार करता है।',
  recovery: 'वसूली एजेंट ऋणदाता का प्राधिकरण पत्र और पहचान पत्र साथ रखते हैं और सुबह 8 बजे से पहले या शाम 7 बजे के बाद कॉल नहीं करते।',
  english: 'अनुवाद और अंग्रेज़ी पाठ में अंतर होने पर अंग्रेज़ी पाठ मान्य होगा।',
}

const mr: Dict = {
  letter: 'मंजुरी पत्र', kfs: 'मुख्य तथ्य विवरण', dear: 'प्रिय',
  intro: 'या पत्रातील आणि खालील मुख्य तथ्य विवरणातील अटींवर आपले कर्ज मंजूर करताना आम्हाला आनंद होत आहे.',
  appNo: 'अर्ज क्रमांक', product: 'उत्पादन', amount: 'मंजूर रक्कम', fee: 'प्रक्रिया शुल्क', gst: 'प्रक्रिया शुल्कावरील जीएसटी',
  insurance: 'विमा', net: 'वितरित निव्वळ रक्कम', rate: 'व्याज दर (वार्षिक, कमी होणाऱ्या शिलकीवर)', apr: 'वार्षिक टक्केवारी दर (एपीआर, सर्व शुल्कांसह)',
  tenure: 'मुदत', months: 'महिने', emi: 'हप्ता (ईएमआय)', count: 'हप्त्यांची संख्या', interest: 'एकूण व्याज', payable: 'एकूण देय रक्कम',
  repayment: 'परतफेडीची पद्धत', cooling: 'कूलिंग-ऑफ (लुक-अप) कालावधी', coolingText: 'दिवस: मुद्दल आणि प्रमाणशीर एपीआर भरून आपण कोणत्याही दंडाशिवाय कर्जातून बाहेर पडू शकता',
  contingent: 'केवळ विशिष्ट परिस्थितीत लागणारे शुल्क', foreclosure: 'मुदतपूर्व परतफेड शुल्क', bounce: 'बाउन्स शुल्क', penal: 'दंड शुल्क',
  conditions: 'मंजुरीच्या अटी', grievance: 'तक्रार निवारण', validity: 'ही मंजुरी या पत्राच्या तारखेपासून 30 दिवस वैध आहे.',
  by: 'मंजूर करणारे', date: 'दिनांक', schedule: 'परतफेड वेळापत्रक', month: 'महिना', principal: 'मुद्दल', balance: 'शिल्लक',
  acceptance: 'कर्जदाराची स्वीकृती', accepted: 'ओटीपीद्वारे स्वीकारले', notAccepted: 'अद्याप स्वीकारलेले नाही. कर्जदार नोंदणीकृत मोबाइलवर आलेल्या ओटीपीने स्वीकारतो.',
  recovery: 'वसुली प्रतिनिधी कर्जदात्याचे अधिकारपत्र आणि ओळखपत्र सोबत ठेवतात आणि सकाळी 8 पूर्वी किंवा संध्याकाळी 7 नंतर फोन करत नाहीत.',
  english: 'भाषांतर आणि इंग्रजी मजकुरात फरक असल्यास इंग्रजी मजकूर ग्राह्य धरला जाईल.',
}

const ta: Dict = {
  letter: 'கடன் அனுமதிக் கடிதம்', kfs: 'முக்கிய விவர அறிக்கை', dear: 'அன்புள்ள',
  intro: 'இந்தக் கடிதம் மற்றும் கீழே உள்ள முக்கிய விவர அறிக்கையின் நிபந்தனைகளின்படி உங்கள் கடனை அனுமதிப்பதில் மகிழ்ச்சி அடைகிறோம்.',
  appNo: 'விண்ணப்ப எண்', product: 'கடன் வகை', amount: 'அனுமதிக்கப்பட்ட தொகை', fee: 'செயலாக்கக் கட்டணம்', gst: 'செயலாக்கக் கட்டணத்தின் மீதான ஜிஎஸ்டி',
  insurance: 'காப்பீடு', net: 'வழங்கப்படும் நிகரத் தொகை', rate: 'வட்டி விகிதம் (ஆண்டுக்கு, குறையும் நிலுவையில்)', apr: 'ஆண்டு சதவீத விகிதம் (APR, அனைத்துக் கட்டணங்களும் உட்பட)',
  tenure: 'கால அளவு', months: 'மாதங்கள்', emi: 'தவணை (EMI)', count: 'தவணைகளின் எண்ணிக்கை', interest: 'மொத்த வட்டி', payable: 'செலுத்த வேண்டிய மொத்தத் தொகை',
  repayment: 'திருப்பிச் செலுத்தும் முறை', cooling: 'பரிசீலனைக் காலம் (கூலிங்-ஆஃப்)', coolingText: 'நாட்கள்: அசலையும் விகிதாசார APR-ஐயும் செலுத்தி அபராதம் இன்றி கடனிலிருந்து வெளியேறலாம்',
  contingent: 'சில நிகழ்வுகளில் மட்டும் விதிக்கப்படும் கட்டணங்கள்', foreclosure: 'முன்கூட்டியே முடிக்கும் கட்டணம்', bounce: 'தவணை திரும்பும் கட்டணம்', penal: 'அபராதக் கட்டணம்',
  conditions: 'அனுமதியின் நிபந்தனைகள்', grievance: 'குறைதீர்ப்பு', validity: 'இந்த அனுமதி இக்கடிதத்தின் தேதியிலிருந்து 30 நாட்கள் செல்லுபடியாகும்.',
  by: 'அனுமதித்தவர்', date: 'தேதி', schedule: 'திருப்பிச் செலுத்தும் அட்டவணை', month: 'மாதம்', principal: 'அசல்', balance: 'நிலுவை',
  acceptance: 'கடன் பெறுபவரின் ஒப்புதல்', accepted: 'OTP மூலம் ஒப்புக்கொள்ளப்பட்டது', notAccepted: 'இன்னும் ஒப்புக்கொள்ளப்படவில்லை. பதிவு செய்த கைபேசிக்கு அனுப்பப்படும் OTP மூலம் ஒப்புக்கொள்ளலாம்.',
  recovery: 'வசூல் முகவர்கள் கடன் வழங்குநரின் அங்கீகாரக் கடிதமும் அடையாள அட்டையும் வைத்திருப்பர்; காலை 8 மணிக்கு முன்போ மாலை 7 மணிக்குப் பின்போ அழைக்க மாட்டார்கள்.',
  english: 'மொழிபெயர்ப்புக்கும் ஆங்கில உரைக்கும் வேறுபாடு இருந்தால் ஆங்கில உரையே செல்லும்.',
}

const ml: Dict = {
  letter: 'വായ്പാ അനുമതി കത്ത്', kfs: 'പ്രധാന വസ്തുതാ പ്രസ്താവന', dear: 'പ്രിയ',
  intro: 'ഈ കത്തിലെയും താഴെയുള്ള പ്രധാന വസ്തുതാ പ്രസ്താവനയിലെയും വ്യവസ്ഥകളോടെ നിങ്ങളുടെ വായ്പ അനുവദിക്കുന്നതിൽ ഞങ്ങൾക്ക് സന്തോഷമുണ്ട്.',
  appNo: 'അപേക്ഷാ നമ്പർ', product: 'വായ്പാ ഇനം', amount: 'അനുവദിച്ച തുക', fee: 'പ്രോസസ്സിംഗ് ഫീസ്', gst: 'പ്രോസസ്സിംഗ് ഫീസിന്മേലുള്ള ജിഎസ്ടി',
  insurance: 'ഇൻഷുറൻസ്', net: 'വിതരണം ചെയ്യുന്ന അറ്റ തുക', rate: 'പലിശ നിരക്ക് (വർഷം തോറും, കുറയുന്ന ബാക്കിയിൽ)', apr: 'വാർഷിക ശതമാന നിരക്ക് (APR, എല്ലാ ചാർജുകളും ഉൾപ്പെടെ)',
  tenure: 'കാലാവധി', months: 'മാസം', emi: 'തവണ (EMI)', count: 'തവണകളുടെ എണ്ണം', interest: 'ആകെ പലിശ', payable: 'അടയ്ക്കേണ്ട ആകെ തുക',
  repayment: 'തിരിച്ചടവ് രീതി', cooling: 'കൂളിംഗ്-ഓഫ് (ലുക്ക്-അപ്പ്) കാലയളവ്', coolingText: 'ദിവസം: മുതലും ആനുപാതിക APR-ഉം അടച്ച് പിഴയില്ലാതെ വായ്പയിൽ നിന്ന് പിന്മാറാം',
  contingent: 'ചില സാഹചര്യങ്ങളിൽ മാത്രം ബാധകമാകുന്ന ചാർജുകൾ', foreclosure: 'മുൻകൂർ അടച്ചുതീർക്കൽ ചാർജ്', bounce: 'ബൗൺസ് ചാർജ്', penal: 'പിഴ ചാർജ്',
  conditions: 'അനുമതിയുടെ വ്യവസ്ഥകൾ', grievance: 'പരാതി പരിഹാരം', validity: 'ഈ അനുമതി ഈ കത്തിന്റെ തീയതി മുതൽ 30 ദിവസത്തേക്ക് സാധുവാണ്.',
  by: 'അനുവദിച്ചത്', date: 'തീയതി', schedule: 'തിരിച്ചടവ് പട്ടിക', month: 'മാസം', principal: 'മുതൽ', balance: 'ബാക്കി',
  acceptance: 'വായ്പക്കാരന്റെ സ്വീകാര്യത', accepted: 'OTP വഴി സ്വീകരിച്ചത്', notAccepted: 'ഇതുവരെ സ്വീകരിച്ചിട്ടില്ല. രജിസ്റ്റർ ചെയ്ത മൊബൈലിലേക്ക് അയയ്ക്കുന്ന OTP വഴി സ്വീകരിക്കാം.',
  recovery: 'റിക്കവറി ഏജന്റുമാർ വായ്പാദാതാവിന്റെ അധികാരപത്രവും തിരിച്ചറിയൽ കാർഡും കൈവശം വയ്ക്കും; രാവിലെ 8-ന് മുമ്പോ വൈകിട്ട് 7-ന് ശേഷമോ വിളിക്കില്ല.',
  english: 'വിവർത്തനവും ഇംഗ്ലീഷ് വാചകവും തമ്മിൽ വ്യത്യാസമുണ്ടെങ്കിൽ ഇംഗ്ലീഷ് വാചകം ബാധകമാകും.',
}

const kn: Dict = {
  letter: 'ಸಾಲ ಮಂಜೂರಾತಿ ಪತ್ರ', kfs: 'ಪ್ರಮುಖ ಸತ್ಯಾಂಶಗಳ ಹೇಳಿಕೆ', dear: 'ಪ್ರಿಯ',
  intro: 'ಈ ಪತ್ರ ಮತ್ತು ಕೆಳಗಿನ ಪ್ರಮುಖ ಸತ್ಯಾಂಶಗಳ ಹೇಳಿಕೆಯ ಷರತ್ತುಗಳ ಮೇಲೆ ನಿಮ್ಮ ಸಾಲವನ್ನು ಮಂಜೂರು ಮಾಡಲು ನಮಗೆ ಸಂತೋಷವಾಗಿದೆ.',
  appNo: 'ಅರ್ಜಿ ಸಂಖ್ಯೆ', product: 'ಸಾಲದ ಬಗೆ', amount: 'ಮಂಜೂರಾದ ಮೊತ್ತ', fee: 'ಪ್ರಕ್ರಿಯಾ ಶುಲ್ಕ', gst: 'ಪ್ರಕ್ರಿಯಾ ಶುಲ್ಕದ ಮೇಲಿನ ಜಿಎಸ್‌ಟಿ',
  insurance: 'ವಿಮೆ', net: 'ವಿತರಿಸುವ ನಿವ್ವಳ ಮೊತ್ತ', rate: 'ಬಡ್ಡಿ ದರ (ವಾರ್ಷಿಕ, ಕಡಿಮೆಯಾಗುವ ಬಾಕಿಯ ಮೇಲೆ)', apr: 'ವಾರ್ಷಿಕ ಶೇಕಡಾ ದರ (APR, ಎಲ್ಲಾ ಶುಲ್ಕಗಳು ಸೇರಿ)',
  tenure: 'ಅವಧಿ', months: 'ತಿಂಗಳು', emi: 'ಕಂತು (EMI)', count: 'ಕಂತುಗಳ ಸಂಖ್ಯೆ', interest: 'ಒಟ್ಟು ಬಡ್ಡಿ', payable: 'ಪಾವತಿಸಬೇಕಾದ ಒಟ್ಟು ಮೊತ್ತ',
  repayment: 'ಮರುಪಾವತಿ ವಿಧಾನ', cooling: 'ಕೂಲಿಂಗ್-ಆಫ್ (ಲುಕ್-ಅಪ್) ಅವಧಿ', coolingText: 'ದಿನಗಳು: ಅಸಲು ಮತ್ತು ಅನುಪಾತದ APR ಪಾವತಿಸಿ ಯಾವುದೇ ದಂಡವಿಲ್ಲದೆ ಸಾಲದಿಂದ ಹೊರಬರಬಹುದು',
  contingent: 'ಕೆಲವು ಸಂದರ್ಭಗಳಲ್ಲಿ ಮಾತ್ರ ಅನ್ವಯಿಸುವ ಶುಲ್ಕಗಳು', foreclosure: 'ಮುಂಗಡ ಮುಕ್ತಾಯ ಶುಲ್ಕ', bounce: 'ಬೌನ್ಸ್ ಶುಲ್ಕ', penal: 'ದಂಡ ಶುಲ್ಕ',
  conditions: 'ಮಂಜೂರಾತಿಯ ಷರತ್ತುಗಳು', grievance: 'ಕುಂದುಕೊರತೆ ಪರಿಹಾರ', validity: 'ಈ ಮಂಜೂರಾತಿ ಈ ಪತ್ರದ ದಿನಾಂಕದಿಂದ 30 ದಿನಗಳವರೆಗೆ ಮಾನ್ಯವಾಗಿರುತ್ತದೆ.',
  by: 'ಮಂಜೂರು ಮಾಡಿದವರು', date: 'ದಿನಾಂಕ', schedule: 'ಮರುಪಾವತಿ ವೇಳಾಪಟ್ಟಿ', month: 'ತಿಂಗಳು', principal: 'ಅಸಲು', balance: 'ಬಾಕಿ',
  acceptance: 'ಸಾಲಗಾರರ ಒಪ್ಪಿಗೆ', accepted: 'OTP ಮೂಲಕ ಒಪ್ಪಲಾಗಿದೆ', notAccepted: 'ಇನ್ನೂ ಒಪ್ಪಿಲ್ಲ. ನೋಂದಾಯಿತ ಮೊಬೈಲ್‌ಗೆ ಕಳುಹಿಸುವ OTP ಮೂಲಕ ಒಪ್ಪಬಹುದು.',
  recovery: 'ವಸೂಲಾತಿ ಏಜೆಂಟರು ಸಾಲದಾತರ ಅಧಿಕಾರ ಪತ್ರ ಮತ್ತು ಗುರುತಿನ ಚೀಟಿ ಹೊಂದಿರುತ್ತಾರೆ; ಬೆಳಿಗ್ಗೆ 8ಕ್ಕೆ ಮೊದಲು ಅಥವಾ ಸಂಜೆ 7ರ ನಂತರ ಕರೆ ಮಾಡುವುದಿಲ್ಲ.',
  english: 'ಅನುವಾದ ಮತ್ತು ಇಂಗ್ಲಿಷ್ ಪಠ್ಯದಲ್ಲಿ ವ್ಯತ್ಯಾಸವಿದ್ದರೆ ಇಂಗ್ಲಿಷ್ ಪಠ್ಯವೇ ಅನ್ವಯಿಸುತ್ತದೆ.',
}

const te: Dict = {
  letter: 'రుణ మంజూరు లేఖ', kfs: 'ముఖ్య వాస్తవాల ప్రకటన', dear: 'ప్రియమైన',
  intro: 'ఈ లేఖ మరియు కింది ముఖ్య వాస్తవాల ప్రకటనలోని నిబంధనలపై మీ రుణాన్ని మంజూరు చేయడానికి మేము సంతోషిస్తున్నాము.',
  appNo: 'దరఖాస్తు సంఖ్య', product: 'రుణ రకం', amount: 'మంజూరైన మొత్తం', fee: 'ప్రాసెసింగ్ ఫీజు', gst: 'ప్రాసెసింగ్ ఫీజుపై జీఎస్టీ',
  insurance: 'బీమా', net: 'పంపిణీ చేసే నికర మొత్తం', rate: 'వడ్డీ రేటు (సంవత్సరానికి, తగ్గే బకాయిపై)', apr: 'వార్షిక శాతం రేటు (APR, అన్ని ఛార్జీలతో కలిపి)',
  tenure: 'కాలవ్యవధి', months: 'నెలలు', emi: 'వాయిదా (EMI)', count: 'వాయిదాల సంఖ్య', interest: 'మొత్తం వడ్డీ', payable: 'చెల్లించవలసిన మొత్తం',
  repayment: 'తిరిగి చెల్లింపు విధానం', cooling: 'కూలింగ్-ఆఫ్ (లుక్-అప్) వ్యవధి', coolingText: 'రోజులు: అసలు మరియు దామాషా APR చెల్లించి ఎలాంటి జరిమానా లేకుండా రుణం నుండి వైదొలగవచ్చు',
  contingent: 'కొన్ని సందర్భాల్లో మాత్రమే వర్తించే ఛార్జీలు', foreclosure: 'ముందస్తు ముగింపు ఛార్జీ', bounce: 'బౌన్స్ ఛార్జీ', penal: 'జరిమానా ఛార్జీ',
  conditions: 'మంజూరు షరతులు', grievance: 'ఫిర్యాదుల పరిష్కారం', validity: 'ఈ మంజూరు ఈ లేఖ తేదీ నుండి 30 రోజుల వరకు చెల్లుబాటు అవుతుంది.',
  by: 'మంజూరు చేసినవారు', date: 'తేదీ', schedule: 'తిరిగి చెల్లింపు షెడ్యూల్', month: 'నెల', principal: 'అసలు', balance: 'బకాయి',
  acceptance: 'రుణగ్రహీత అంగీకారం', accepted: 'OTP ద్వారా అంగీకరించారు', notAccepted: 'ఇంకా అంగీకరించలేదు. నమోదైన మొబైల్‌కు పంపే OTP ద్వారా అంగీకరించవచ్చు.',
  recovery: 'వసూలు ఏజెంట్లు రుణదాత అధికార లేఖ మరియు గుర్తింపు కార్డు కలిగి ఉంటారు; ఉదయం 8 ముందు లేదా సాయంత్రం 7 తర్వాత కాల్ చేయరు.',
  english: 'అనువాదానికి మరియు ఆంగ్ల పాఠానికి తేడా ఉంటే ఆంగ్ల పాఠమే వర్తిస్తుంది.',
}

const bn: Dict = {
  letter: 'ঋণ অনুমোদন পত্র', kfs: 'মূল তথ্য বিবরণী', dear: 'প্রিয়',
  intro: 'এই পত্র এবং নিচের মূল তথ্য বিবরণীর শর্তে আপনার ঋণ অনুমোদন করতে পেরে আমরা আনন্দিত।',
  appNo: 'আবেদন নম্বর', product: 'ঋণের ধরন', amount: 'অনুমোদিত পরিমাণ', fee: 'প্রসেসিং ফি', gst: 'প্রসেসিং ফি-র উপর জিএসটি',
  insurance: 'বিমা', net: 'বিতরণ করা নিট পরিমাণ', rate: 'সুদের হার (বার্ষিক, হ্রাসমান বকেয়ার উপর)', apr: 'বার্ষিক শতাংশ হার (APR, সব চার্জ সহ)',
  tenure: 'মেয়াদ', months: 'মাস', emi: 'কিস্তি (EMI)', count: 'কিস্তির সংখ্যা', interest: 'মোট সুদ', payable: 'মোট প্রদেয় পরিমাণ',
  repayment: 'পরিশোধের পদ্ধতি', cooling: 'কুলিং-অফ (লুক-আপ) সময়কাল', coolingText: 'দিন: আসল এবং আনুপাতিক APR পরিশোধ করে কোনো জরিমানা ছাড়াই ঋণ থেকে বেরিয়ে আসতে পারেন',
  contingent: 'কেবল নির্দিষ্ট ক্ষেত্রে প্রযোজ্য চার্জ', foreclosure: 'আগাম পরিশোধ চার্জ', bounce: 'বাউন্স চার্জ', penal: 'জরিমানা চার্জ',
  conditions: 'অনুমোদনের শর্তাবলি', grievance: 'অভিযোগ নিষ্পত্তি', validity: 'এই অনুমোদন এই পত্রের তারিখ থেকে 30 দিন বৈধ।',
  by: 'অনুমোদনকারী', date: 'তারিখ', schedule: 'পরিশোধের সূচি', month: 'মাস', principal: 'আসল', balance: 'বকেয়া',
  acceptance: 'ঋণগ্রহীতার সম্মতি', accepted: 'OTP-র মাধ্যমে সম্মতি দেওয়া হয়েছে', notAccepted: 'এখনও সম্মতি দেওয়া হয়নি। নিবন্ধিত মোবাইলে পাঠানো OTP দিয়ে সম্মতি দেওয়া যায়।',
  recovery: 'আদায় এজেন্টরা ঋণদাতার অনুমোদন পত্র ও পরিচয়পত্র সঙ্গে রাখেন এবং সকাল 8টার আগে বা সন্ধ্যা 7টার পরে ফোন করেন না।',
  english: 'অনুবাদ এবং ইংরেজি পাঠে পার্থক্য থাকলে ইংরেজি পাঠই প্রযোজ্য।',
}

const gu: Dict = {
  letter: 'લોન મંજૂરી પત્ર', kfs: 'મુખ્ય તથ્ય નિવેદન', dear: 'પ્રિય',
  intro: 'આ પત્ર અને નીચેના મુખ્ય તથ્ય નિવેદનની શરતો પર આપની લોન મંજૂર કરતાં અમને આનંદ થાય છે.',
  appNo: 'અરજી નંબર', product: 'લોનનો પ્રકાર', amount: 'મંજૂર રકમ', fee: 'પ્રોસેસિંગ ફી', gst: 'પ્રોસેસિંગ ફી પર જીએસટી',
  insurance: 'વીમો', net: 'વિતરિત ચોખ્ખી રકમ', rate: 'વ્યાજ દર (વાર્ષિક, ઘટતી બાકી રકમ પર)', apr: 'વાર્ષિક ટકાવારી દર (APR, તમામ ચાર્જ સહિત)',
  tenure: 'મુદત', months: 'મહિના', emi: 'હપ્તો (EMI)', count: 'હપ્તાઓની સંખ્યા', interest: 'કુલ વ્યાજ', payable: 'કુલ ચૂકવવાપાત્ર રકમ',
  repayment: 'ચુકવણીની રીત', cooling: 'કૂલિંગ-ઓફ (લુક-અપ) સમયગાળો', coolingText: 'દિવસ: મુદ્દલ અને પ્રમાણસર APR ચૂકવીને કોઈ દંડ વિના લોનમાંથી બહાર નીકળી શકો છો',
  contingent: 'ફક્ત અમુક સંજોગોમાં લાગુ પડતા ચાર્જ', foreclosure: 'વહેલી ચુકવણી ચાર્જ', bounce: 'બાઉન્સ ચાર્જ', penal: 'દંડ ચાર્જ',
  conditions: 'મંજૂરીની શરતો', grievance: 'ફરિયાદ નિવારણ', validity: 'આ મંજૂરી આ પત્રની તારીખથી 30 દિવસ માટે માન્ય છે.',
  by: 'મંજૂર કરનાર', date: 'તારીખ', schedule: 'ચુકવણી સમયપત્રક', month: 'મહિનો', principal: 'મુદ્દલ', balance: 'બાકી',
  acceptance: 'ઉધાર લેનારની સંમતિ', accepted: 'OTP દ્વારા સ્વીકાર્યું', notAccepted: 'હજુ સ્વીકાર્યું નથી. નોંધાયેલા મોબાઇલ પર મોકલાયેલા OTP થી સ્વીકારી શકાય છે.',
  recovery: 'વસૂલાત એજન્ટો ધિરાણકર્તાનો અધિકૃતતા પત્ર અને ઓળખપત્ર સાથે રાખે છે અને સવારે 8 પહેલાં કે સાંજે 7 પછી ફોન કરતા નથી.',
  english: 'અનુવાદ અને અંગ્રેજી લખાણમાં તફાવત હોય તો અંગ્રેજી લખાણ માન્ય રહેશે.',
}

export const KFS_TEXT: Record<string, Dict> = { en, hi, mr, ta, ml, kn, te, bn, gu }

/** English label with the borrower-language label when it is not English. */
export function both(lang: string, key: string): { en: string; local?: string } {
  const e = en[key] ?? key
  const l = lang !== 'en' ? KFS_TEXT[lang]?.[key] : undefined
  return { en: e, local: l && l !== e ? l : undefined }
}

export const CHARGE_KEY: Record<string, string> = { 'Foreclosure charge': 'foreclosure', 'Bounce charge': 'bounce', 'Penal charge': 'penal' }
