import { readFileSync } from "node:fs";
import { cityPagePath, translations } from "../landing/locales.js";

const read = path => readFileSync(new URL(path, import.meta.url), "utf8");
const names = JSON.parse(read("../transitBff/src/main/resources/transit/borjomi/names.json"));
const lines = read("../transitBff/data/borjomi/stops.csv").trimEnd().split(/\r?\n/);
const directions = new Map();
for (const line of lines.slice(1)) {
  // The BFF catalog stores one stop per line. Quoted names may include commas.
  const fields = [...line.matchAll(/(?:^|,)(?:"((?:[^"]|"")*)"|([^",]*))(?=,|$)/g)]
    .map(match => (match[1] ?? match[2]).replaceAll('""', '"'));
  const [route, direction, , , sequence, , name] = fields;
  const key = route + "-" + direction;
  if (!directions.has(key)) directions.set(key, []);
  const stops = directions.get(key);
  if (fields.length !== 10 || !names[name] || Number(sequence) !== stops.length + 1) {
    throw new Error(`Invalid Borjomi stop in ${key}: ${line}`);
  }
  stops.push({ name });
}

export const borjomiTrips = [...new Set([...directions.keys()].map(key => key.split("-")[0]))];
export const borjomiTripPath = (route, lang) => cityPagePath(
  route === "5" ? "borjomi-market-new-district" : `borjomi-bus-${route}`, lang,
);
const copy = {
  th: ["เส้นทางจากตลาด", "เปิดแผนที่รถบัส", "ป้ายทั้งหมด", "ค่าโดยสาร", "การตัดสินใจของเทศบาลเมื่อวันที่ 26 มีนาคม พ.ศ. 2567 กำหนดค่าโดยสาร 0.20 GEL สำหรับเส้นทางทั้งห้านี้", "การตัดสินใจค่าโดยสารเทศบาล", "แผนภาพเส้นทางรถบัสบอร์โจมี", "เส้นเชื่อมต่อจุดหยุดติดต่อกัน เลือกทิศทางเพื่อดูจุดจอดตามลำดับการเดินทาง", "ป้ายในขาไป", "ป้ายในขากลับ", "รถประจำทาง"],
  en: ["Routes from Market", "Open the bus map", "All stops", "Fare", "The municipal decision of 26 March 2024 lists a fare of 0.20 GEL on these five routes.", "Municipal fare decision", "Borjomi bus route diagram", "Lines connect consecutive stops. Select a direction to see the stops in travel order.", "outbound stops", "return stops", "Bus"],
  ru: ["Маршруты от Рынка", "Открыть карту автобусов", "Все остановки", "Стоимость проезда", "В постановлении муниципалитета от 26 марта 2024 года для этих пяти маршрутов указан тариф 0,20 лари.", "Постановление о тарифах", "Схема автобусных маршрутов Боржоми", "Линии соединяют соседние остановки. Выберите направление, чтобы посмотреть остановки по порядку.", "остановок туда", "обратно", "Автобус"],
  ka: ["მარშრუტები ბაზრიდან", "ავტობუსების რუკის გახსნა", "ყველა გაჩერება", "მგზავრობის საფასური", "მუნიციპალიტეტის 2024 წლის 26 მარტის დადგენილებით ამ ხუთ მარშრუტზე მგზავრობის საფასური 0,20 ლარია.", "დადგენილება ტარიფების შესახებ", "ბორჯომის ავტობუსების მარშრუტების სქემა", "ხაზები მიმდევრობით აკავშირებს გაჩერებებს. აირჩიეთ მიმართულება გაჩერებების სიის სანახავად.", "გაჩერება ბაზრიდან", "უკან", "ავტობუსი"],
  de: ["Linien ab dem Markt", "Buskarte öffnen", "Alle Haltestellen", "Fahrpreis", "Der Gemeindebeschluss vom 26. März 2024 nennt für diese fünf Linien einen Fahrpreis von 0,20 GEL.", "Tarifbeschluss der Gemeinde", "Linienplan der Busse in Bordschomi", "Die Linien verbinden aufeinanderfolgende Haltestellen. Wählen Sie eine Fahrtrichtung für die Liste in Fahrtreihenfolge.", "Haltestellen auf der Hinfahrt", "auf der Rückfahrt", "Bus"],
  fr: ["Lignes au départ du marché", "Ouvrir la carte des bus", "Tous les arrêts", "Tarif", "La délibération municipale du 26 mars 2024 indique un tarif de 0,20 GEL pour ces cinq lignes.", "Délibération sur les tarifs", "Schéma des lignes de bus de Bordjomi", "Les lignes relient les arrêts successifs. Choisissez un sens pour consulter les arrêts dans leur ordre de passage.", "arrêts à l’aller", "au retour", "Bus"],
  "zh-Hans": ["从市场出发的线路", "打开公交地图", "全部站点", "票价", "根据2024年3月26日的市政决议，这五条线路的票价为0.20拉里。", "市政票价决议", "博尔若米公交线路示意图", "线段连接相邻站点。选择行车方向即可按顺序查看站点。", "站（去程）", "站（返程）", "公交"],
};

function trip(route, lang) {
  const localized = name => names[name][lang === "zh-Hans" ? "zh" : lang];
  const legs = ["1", "2"].map(direction => directions.get(route + "-" + direction)
    .map(stop => ({ ...stop, name: localized(stop.name) })));
  const [out, back] = legs;
  const label = out[0].name + " → " + out.at(-1).name;
  const title = route === "5" ? label : `${copy[lang][10]} ${route} · ${label}`;
  const counts = `${out.length} ${copy[lang][8]} · ${back.length} ${copy[lang][9]}`;
  return { title, counts, legs };
}

const map = "https://app.geotransit.com.ge/?city=borjomi";
export function borjomiCityPage(lang, esc) {
  const { borjomi: title, borjomiBody: description, linkTelegram } = translations[lang];
  const [routes, button, , fare, price, decision, alt, caption] = copy[lang];
  // Percentage positions of the five outbound termini on the route diagram.
  const termini = { "1": [15.5, 86.6], "2": [56.7, 93.5], "3": [67.6, 49.8], "4": [82.8, 7.5], "5": [39.1, 86.4] };
  const markers = borjomiTrips.map(route => {
    const { title, legs } = trip(route, lang);
    const [x, y] = termini[route];
    const label = route === "5" ? legs[0].at(-1).name : route;
    return `<a class="diagram-stop" href="${borjomiTripPath(route, lang)}" style="left:${x}%;top:${y}%" aria-label="${esc(title)}">${esc(label)}</a>`;
  }).join("");
  const cards = borjomiTrips.map(route => {
    const { title, counts } = trip(route, lang);
    return `<article><h3><a href="${borjomiTripPath(route, lang)}">${esc(title)}</a></h3><p>${esc(counts)}</p></article>`;
  }).join("\n");
  const main = `<section class="city-hero" data-city="borjomi"><div class="wrap city-hero__grid">
    <div class="city-hero__copy"><h1>${esc(title)}</h1><p>${esc(description)}</p>
      <div class="city-hero__actions"><a class="button city-guide__cta" data-analytics-platform="web" href="${map}">${button}</a>
      <a data-analytics-platform="telegram_mini_app" href="https://t.me/geo_transit_bot?startapp">${esc(linkTelegram)}</a></div></div>
    <figure class="borjomi-diagram"><div class="diagram-lines"><img src="/assets/screens/borjomi-routes.webp" width="960" height="690" alt="${alt}">${markers}</div></figure></div></section>
    <section class="wrap batumi-trips" id="routes"><h2>${routes}</h2><p>${caption}</p><div class="trip-grid">${cards}</div></section>
    <section class="wrap route-fare"><h2>${fare}</h2><p>${price}</p><a href="https://www.matsne.gov.ge/ka/document/download/6137986/0/ge/pdf">${decision}</a></section>`;
  return { title: title + " | Georgia Transit", description, main };
}

export function borjomiTripPage(route, lang, esc) {
  const { title, counts, legs } = trip(route, lang);
  const city = translations[lang].borjomi;
  const sections = legs.map(stops => `<section><h2>${esc(stops[0].name)} → ${esc(stops.at(-1).name)}</h2>
    <details open><summary>${copy[lang][2]} (${stops.length})</summary><ol>${stops.map(stop =>
      `<li>${esc(stop.name)}</li>`).join("")}</ol></details></section>`).join("\n");
  const main = `<div class="wrap route-detail"><a href="${cityPagePath("borjomi", lang)}#routes">← ${esc(city)}</a>
    <h1>${esc(title)}</h1><p>${esc(counts)}</p><a class="button city-guide__cta" data-analytics-platform="web" href="${map}&amp;route=${encodeURIComponent("borjomi:static:route:" + route)}">${copy[lang][1]}</a>
    <div class="trip-directions">${sections}</div></div>`;
  return { title: `${title} | ${city} | Georgia Transit`, description: `${city} · ${title}. ${counts}.`, main };
}
