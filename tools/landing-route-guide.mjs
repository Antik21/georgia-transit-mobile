import { readFileSync } from "node:fs";

// Canonical route IDs and direction names from the production Transit BFF, 2026-11-25.
const routeCatalogs = Object.fromEntries(["batumi", "kutaisi", "zugdidi"].map(city => [
  city, JSON.parse(readFileSync(new URL(city === "kutaisi"
    ? "../transitBff/src/main/resources/transit/kutaisi/route-names.json" : `./${city}-routes.json`, import.meta.url), "utf8")),
]));

const labels = {
  th: ["เปิดแผนที่รถบัสบาตูมี", "เส้นทางรถบัสบาตูมี", "เส้นทางเพิ่มเติม", "ค่าโดยสารและการชำระเงิน", "0.70 ลารี", "ค่าโดยสารมาตรฐานพร้อมบัตรเดินทางแบบโหลดซ้ำได้ ซึ่งออกให้ฟรี คุณยังสามารถแตะบัตรธนาคารแบบไร้สัมผัสหรือโทรศัพท์ด้วยบัตรใน Apple Pay หรือ Google Pay ได้อีกด้วย Bank of Georgia ยืนยันสิ่งนี้สำหรับบัตร Visa และ Mastercard", "การตัดสินใจค่าโดยสารเมือง", "บัตรเดินทาง", "ตรวจสอบเมื่อ 25 พฤศจิกายน 2569"],
  en: ["Open the Batumi bus map", "Batumi bus routes", "More routes", "Fare and payment", "0.70 GEL", "Standard fare with a reloadable travel card, issued free of charge. You can also tap a contactless bank card or a phone with the card in Apple Pay or Google Pay. Bank of Georgia confirms this for its Visa and Mastercard cards.", "City fare decision", "Travel card", "Checked 25 November 2026"],
  ru: ["Открыть карту автобусов Батуми", "Маршруты автобусов Батуми", "Ещё маршруты", "Проезд и оплата", "0,70 ₾", "Стандартный тариф по пополняемой транспортной карте. Саму карту выдают бесплатно. Можно также приложить к валидатору бесконтактную банковскую карту или телефон с картой в Apple Pay или Google Pay. Bank of Georgia подтверждает оба способа для своих Visa и Mastercard.", "Постановление о тарифе", "Как получить карту", "Проверено 25 ноября 2026"],
  ka: ["ბათუმის ავტობუსების რუკის გახსნა", "ბათუმის ავტობუსების მარშრუტები", "სხვა მარშრუტები", "მგზავრობის საფასური და გადახდა", "0,70 ₾", "სტანდარტული ტარიფი შევსებადი სატრანსპორტო ბარათით მგზავრობისას. ბარათი უფასოდ გაიცემა. ვალიდატორზე ასევე შეგიძლიათ მიადოთ უკონტაქტო საბანკო ბარათი ან ტელეფონი, თუ ბარათი Apple Pay-ში ან Google Pay-ში გაქვთ დამატებული. საქართველოს ბანკი ორივე მეთოდს თავისი Visa და Mastercard ბარათებისთვის ადასტურებს.", "დადგენილება ტარიფის შესახებ", "სატრანსპორტო ბარათი", "გადამოწმებულია 2026 წლის 25 ნოემბერს"],
  de: ["Buskarte von Batumi öffnen", "Buslinien in Batumi", "Weitere Linien", "Fahrpreis und Bezahlung", "0,70 GEL", "Regulärer Fahrpreis mit der kostenlos erhältlichen, aufladbaren Fahrkarte. Sie können auch eine kontaktlose Bankkarte oder ein Smartphone mit der in Apple Pay oder Google Pay hinterlegten Karte ans Lesegerät halten. Die Bank of Georgia bestätigt beide Möglichkeiten für ihre Visa- und Mastercard-Karten.", "Städtische Tarifregelung", "Fahrkarte erhalten", "Geprüft am 25. November 2026"],
  fr: ["Ouvrir le plan des bus de Batoumi", "Lignes de bus de Batoumi", "Autres lignes", "Tarif et paiement", "0,70 GEL", "Tarif normal avec une carte de transport rechargeable, délivrée gratuitement. Vous pouvez aussi présenter une carte bancaire sans contact ou un téléphone sur lequel la carte est enregistrée dans Apple Pay ou Google Pay. Bank of Georgia confirme ces deux modes de paiement pour ses cartes Visa et Mastercard.", "Décision municipale sur le tarif", "Obtenir la carte", "Vérifié le 25 novembre 2026"],
  "zh-Hans": ["打开巴统公交地图", "巴统公交线路", "更多线路", "票价与支付", "0.70 拉里", "这是使用可充值交通卡的普通单程票价，领卡免费。也可以在读卡器上轻触非接触式银行卡，或使用已将银行卡添加到 Apple Pay 或 Google Pay 的手机付款。格鲁吉亚银行确认，该行发行的 Visa 和 Mastercard 均支持这两种方式。", "市政票价规定", "领取交通卡", "核实日期：2026年11月25日"],
};

const kutaisiCopy = {
  th: ["เปิดแผนที่รถบัส คูไตซี", "เส้นทางรถเมล์คูไตซี", "0.60 ลารี", "อัตราค่าโดยสารมาตรฐานเทศบาล แตะ Bank of Georgia Visa หรือ Mastercard ที่เครื่องอ่าน หรือใช้บัตรใบเดียวกันใน Apple Pay หรือ Google Pay"],
  en: ["Open the Kutaisi bus map", "Kutaisi bus routes", "0.60 GEL", "Standard municipal bus fare. Tap a Bank of Georgia Visa or Mastercard at the reader, or use the same card in Apple Pay or Google Pay."],
  ru: ["Открыть карту автобусов Кутаиси", "Маршруты автобусов Кутаиси", "0,60 ₾", "Обычный тариф на городских автобусах. Приложите к валидатору Visa или Mastercard от Bank of Georgia либо телефон с этой картой в Apple Pay или Google Pay."],
  ka: ["ქუთაისის ავტობუსების რუკის გახსნა", "ქუთაისის ავტობუსების მარშრუტები", "0,60 ₾", "მუნიციპალური ავტობუსით მგზავრობის სტანდარტული ტარიფი. ვალიდატორს მიადეთ საქართველოს ბანკის Visa ან Mastercard ბარათი ან ტელეფონი, თუ ეს ბარათი Apple Pay-ში ან Google Pay-ში გაქვთ დამატებული."],
  de: ["Buskarte von Kutaissi öffnen", "Buslinien in Kutaissi", "0,60 GEL", "Regulärer Fahrpreis im Stadtbus. Halten Sie Ihre Visa oder Mastercard der Bank of Georgia ans Lesegerät oder bezahlen Sie mit derselben Karte über Apple Pay oder Google Pay."],
  fr: ["Ouvrir le plan des bus de Koutaïssi", "Lignes de bus de Koutaïssi", "0,60 GEL", "Tarif normal d’un trajet en bus municipal. Présentez votre carte Visa ou Mastercard de Bank of Georgia au valideur, ou utilisez cette carte sur votre téléphone avec Apple Pay ou Google Pay."],
  "zh-Hans": ["打开库塔伊西公交地图", "库塔伊西公交线路", "0.60 拉里", "市营公交普通单程票价。可在读卡器上轻触格鲁吉亚银行发行的 Visa 或 Mastercard，也可使用绑定了该卡的 Apple Pay 或 Google Pay 付款。"],
};

const zugdidiCopy = {
  th: ["เปิดแผนที่รถบัส ซุกดิดี", "เส้นทางรถเมล์ ซุกดิดี", "0.80 ลารี", "ค่านั่งรถบัสในเมืองราคา 0.80 GEL เส้นทางไปยังหมู่บ้านราคา 1 หรือ 1.50 GEL ขึ้นอยู่กับเส้นทาง คุณสามารถชำระเงินด้วย Bank of Georgia Visa หรือ Mastercard รวมถึงผ่าน Apple Pay หรือ Google Pay"],
  en: ["Open the Zugdidi bus map", "Zugdidi bus routes", "0.80 GEL", "A city bus ride costs 0.80 GEL. Routes to villages cost 1 or 1.50 GEL depending on the route. You can pay with a Bank of Georgia Visa or Mastercard, including through Apple Pay or Google Pay."],
  ru: ["Открыть карту автобусов Зугдиди", "Маршруты автобусов Зугдиди", "0,80 ₾", "Столько стоит поездка по городу. На рейсах в сёла тариф зависит от маршрута — 1 или 1,50 ₾. Можно платить картой Visa или Mastercard от Bank of Georgia либо телефоном с этой картой в Apple Pay или Google Pay."],
  ka: ["ზუგდიდის ავტობუსების რუკის გახსნა", "ზუგდიდის ავტობუსების მარშრუტები", "0,80 ₾", "ქალაქში მგზავრობა 80 თეთრი ღირს. სოფლების მიმართულებით მგზავრობის საფასური მარშრუტის მიხედვით 1 ან 1,50 ლარია. გადახდა შეგიძლიათ საქართველოს ბანკის Visa ან Mastercard ბარათით, მათ შორის Apple Pay-სა და Google Pay-ში დამატებული ბარათით."],
  de: ["Buskarte von Sugdidi öffnen", "Buslinien in Sugdidi", "0,80 GEL", "Eine Fahrt im Stadtbus kostet 0,80 GEL. Auf den Linien in die Dörfer kostet eine Fahrt je nach Strecke 1 oder 1,50 GEL. Sie können mit einer Visa oder Mastercard der Bank of Georgia bezahlen, auch über Apple Pay oder Google Pay."],
  fr: ["Ouvrir le plan des bus de Zougdidi", "Lignes de bus de Zougdidi", "0,80 GEL", "Un trajet en ville coûte 0,80 GEL. Vers les villages, le tarif est de 1 ou 1,50 GEL selon la ligne. Vous pouvez payer avec une carte Visa ou Mastercard de Bank of Georgia, y compris via Apple Pay ou Google Pay."],
  "zh-Hans": ["打开祖格迪迪公交地图", "祖格迪迪公交线路", "0.80 拉里", "市内公交单程票价为 0.80 拉里。前往村庄的线路按路线收费，单程为 1 或 1.50 拉里。可使用格鲁吉亚银行发行的 Visa 或 Mastercard，也可通过绑定该卡的 Apple Pay 或 Google Pay 付款。"],
};

const cityCopy = { kutaisi: kutaisiCopy, zugdidi: zugdidiCopy };
const fareDecisions = { batumi: "6766870", kutaisi: "5709565", zugdidi: "6885611" };

export function routeGuide(city, language, escape) {
  const routes = routeCatalogs[city];
  if (!routes) return null;
  const [batumiButton, batumiHeading, more, fare, batumiPrice, batumiPayment, decision, card, checked] = labels[language];
  const [button, heading, price, payment] = cityCopy[city]?.[language] ?? [batumiButton, batumiHeading, batumiPrice, batumiPayment];
  const map = `https://app.geotransit.com.ge/?city=${city}`;
  const rows = routes.map(route => {
    const name = route.name[language === "zh-Hans" ? "zh" : language];
    return `<li><a href="${map}&amp;route=${encodeURIComponent(route.id)}"><b>${escape(route.number)}</b><span>${escape(name)}</span><span aria-hidden="true">↗</span></a></li>`;
  });
  const paymentSource = language === "ru" ? "https://visitbatumi.com/ru/munitsipalnyi-transport-666" : "https://visitbatumi.com/en/public-transport-981";
  const html = `<div class="wrap route-guide">
      <section aria-labelledby="${city}-routes">
        <h2 id="${city}-routes">${heading}</h2>
        <ul>${rows.slice(0, 6).join("\n")}</ul>
        <details><summary>${more} (${rows.length - 6})</summary><ul>${rows.slice(6).join("\n")}</ul></details>
      </section>
      <section class="route-fare" aria-labelledby="${city}-fare">
        <h2 id="${city}-fare">${fare}</h2>
        <strong>${price}</strong>
        <p>${payment}</p>
        <a href="https://matsne.gov.ge/ka/document/view/${fareDecisions[city]}">${decision}</a>
        <a href="https://bankofgeorgia.ge/blog/ganatleba/finansuri-ganatleba/municipalur-transportshi-saqartvelos-bankis-baratit-gadaxdis-shesadzlebloba-kidev-ramdenime-qalaqshi-gachnda/">Bank of Georgia · Visa, Mastercard, Apple Pay, Google Pay</a>${city === "batumi" ? `
        <a href="${paymentSource}">${card} · Visit Batumi</a>` : ""}
        <small>${checked}</small>
      </section>
    </div>`;
  return { button, map, html };
}
