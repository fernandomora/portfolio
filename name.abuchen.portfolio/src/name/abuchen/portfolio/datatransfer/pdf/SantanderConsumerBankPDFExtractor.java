package name.abuchen.portfolio.datatransfer.pdf;

import static name.abuchen.portfolio.datatransfer.ExtractorUtils.checkAndSetGrossUnit;
import static name.abuchen.portfolio.util.TextUtil.trim;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import name.abuchen.portfolio.datatransfer.ExtrExchangeRate;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Block;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.DocumentType;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Transaction;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.util.TextUtil;

@SuppressWarnings("nls")
public class SantanderConsumerBankPDFExtractor extends AbstractPDFExtractor
{
    public SantanderConsumerBankPDFExtractor(Client client)
    {
        super(client);

        addBankIdentifier("Santander Consumer Bank AG");
        addBankIdentifier("Santander Consumer Bank GmbH");

        addBuySellTransaction();
        addDividendeTransaction();
        addAccountStatementTransaction();
    }

    @Override
    public String getLabel()
    {
        return "Santander Consumer Bank";
    }

    private void addBuySellTransaction()
    {
        DocumentType type = new DocumentType("Wertpapier Abrechnung Kauf");
        this.addDocumentTyp(type);

        Transaction<BuySellEntry> pdfTransaction = new Transaction<>();
        pdfTransaction.subject(() -> {
            BuySellEntry entry = new BuySellEntry();
            entry.setType(PortfolioTransaction.Type.BUY);
            return entry;
        });

        Block firstRelevantLine = new Block("^Wertpapier Abrechnung Kauf.*$");
        type.addBlock(firstRelevantLine);
        firstRelevantLine.set(pdfTransaction);

        pdfTransaction
                // Stück 2 3M CO. US88579Y1010 (851745)
                // REGISTERED SHARES DL -,01
                // Kurswert 317,96- EUR
                .section("name", "isin", "wkn", "nameContinued", "currency")
                .find("Nominale Wertpapierbezeichnung ISIN \\(WKN\\)")
                .match("^St.ck [\\.,\\d]+ (?<name>.*) (?<isin>[\\w]{12}) (\\((?<wkn>.*)\\))$")
                .match("^(?<nameContinued>.*)$")
                .match("^Kurswert [\\.,\\d]+\\- (?<currency>[\\w]{3})$")
                .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))

                // Stück 13 VANGUARD FTSE ALL-WORLD U.ETF      IE00B3RBWM25 (A1JX52)
                .section("shares")
                .match("^St.ck (?<shares>[\\.,\\d]+) .*$")
                .assign((t, v) -> t.setShares(asShares(v.get("shares"))))

                // Schlusstag/-Zeit 17.03.2021 16:53:45 Auftraggeber NACHNAME VORNAME
                .section("date", "time")
                .match("^Schlusstag\\/\\-Zeit (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) (?<time>[\\d]{2}:[\\d]{2}:[\\d]{2}) .*$")
                .assign((t, v) -> t.setDate(asDate(v.get("date"), v.get("time"))))

                // Ausmachender Betrag 325,86- EUR
                .section("amount", "currency")
                .match("^Ausmachender Betrag (?<amount>[\\.,\\d]+)\\- (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                })

                // Limit billigst
                .section("note").optional()
                .match("^(?<note>Limit .*)$")
                .assign((t, v) -> t.setNote(trim(v.get("note"))))

                .wrap(BuySellEntryItem::new);

        addFeesSectionsTransaction(pdfTransaction, type);
    }

    private void addDividendeTransaction()
    {
        DocumentType type = new DocumentType("Dividendengutschrift");
        this.addDocumentTyp(type);

        Block block = new Block("^Dividendengutschrift$");
        type.addBlock(block);
        Transaction<AccountTransaction> pdfTransaction = new Transaction<AccountTransaction>().subject(() -> {
            AccountTransaction entry = new AccountTransaction();
            entry.setType(AccountTransaction.Type.DIVIDENDS);
            return entry;
        });

        pdfTransaction
                // Nominale Wertpapierbezeichnung ISIN (WKN)
                // Stück 2 3M CO. US88579Y1010 (851745)
                // REGISTERED SHARES DL -,01
                // Zahlbarkeitstag 14.06.2021 Dividende pro Stück 1,48 USD
                .section("name", "isin", "wkn", "nameContinued", "currency")
                .find("Nominale Wertpapierbezeichnung ISIN \\(WKN\\)")
                .match("^St.ck [\\.,\\d]+ (?<name>.*) (?<isin>[\\w]{12}) (\\((?<wkn>.*)\\))$")
                .match("^(?<nameContinued>.*)$")
                .match("^Zahlbarkeitstag .* [\\.,\\d]+ (?<currency>[\\w]{3})$")
                .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))

                // Stück 2 3M CO. US88579Y1010 (851745)
                .section("shares")
                .match("^St.ck (?<shares>[\\.,\\d]+) .* (\\(.*\\))$")
                .assign((t, v) -> t.setShares(asShares(v.get("shares"))))

                // Den Betrag buchen wir mit Wertstellung 16.06.2021 zu Gunsten des Kontos 0000000000 (IBAN XX00 0000 0000 0000
                .section("date")
                .match("^Den Betrag buchen wir mit Wertstellung (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) .*$")
                .assign((t, v) -> t.setDateTime(asDate(v.get("date"))))

                // Ausmachender Betrag 2,07+ EUR
                .section("amount", "currency")
                .match("^Ausmachender Betrag (?<amount>[\\.,\\d]+)\\+ (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                })

                // Devisenkurs EUR / USD 1,2137
                // Dividendengutschrift 2,96 USD 2,44+ EUR
                .section("baseCurrency", "termCurrency", "exchangeRate", "fxGross", "fxCurrency", "gross", "currency").optional()
                .match("^Devisenkurs (?<baseCurrency>[\\w]{3}) \\/ (?<termCurrency>[\\w]{3}) ([\\s]+)?(?<exchangeRate>[\\.,\\d]+)$")
                .match("^Dividendengutschrift (?<fxGross>[\\.,\\d]+) (?<fxCurrency>[\\w]{3}) (?<gross>[\\.,\\d]+)\\+ (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    ExtrExchangeRate rate = asExchangeRate(v);
                    type.getCurrentContext().putType(rate);

                    Money gross = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("gross")));
                    Money fxGross = Money.of(asCurrencyCode(v.get("fxCurrency")), asAmount(v.get("fxGross")));

                    checkAndSetGrossUnit(gross, fxGross, t, type.getCurrentContext());
                })

                // Ex-Tag 20.05.2021 Art der Dividende Quartalsdividende
                .section("note").optional()
                .match("^.* Art der Dividende (?<note>.*)$")
                .assign((t, v) -> t.setNote(trim(v.get("note"))))

                .wrap(TransactionItem::new);

        addTaxesSectionsTransaction(pdfTransaction, type);
        addFeesSectionsTransaction(pdfTransaction, type);

        block.set(pdfTransaction);
    }
    
    private void addAccountStatementTransaction()
    {
        DocumentType type = new DocumentType("Kontoauszug", (context, lines) -> {
            // Find currency
            // € 0,00 € 38,98 € 40,64 € 1,66 € 6,63 € 1,66
            final Pattern pCurrency = Pattern
                            .compile("^(?<currency>[\\w]{1}\\p{Sc}) [\\.,\\d]+ [\\w]{1}\\p{Sc} [\\.,\\d]+.*");

            for (String line : lines)
            {
                Matcher mCurrency = pCurrency.matcher(line);
                if (mCurrency.matches())
                {
                    context.put("currency", mCurrency.group("currency"));
                    break;
                }
            }
        });
        this.addDocumentTyp(type);

        // 31.05.2023 31.05.2023 -1,66 38,98 Kapitalertragsteuer
        Block taxesBlock = new Block("^(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) ([\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) (?<amount>[\\.,\\d-]+) ([\\.,\\d-]+) (Kapitalertragsteuer).*");
        type.addBlock(taxesBlock);
        taxesBlock.set(new Transaction<AccountTransaction>()

                        .subject(() -> {
                            AccountTransaction entry = new AccountTransaction();
                            entry.setType(AccountTransaction.Type.TAXES);
                            return entry;
                        })

                        .section("date", "amount")
                        .match("^(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) ([\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) (?<amount>[\\.,\\d-]+) ([\\.,\\d-]+) (Kapitalertragsteuer).*")
                        .assign((t, v) -> {
                            Map<String, String> context = type.getCurrentContext();

                            t.setDateTime(asDate(v.get("date")));
                            t.setAmount(asAmount(v.get("amount")));
                            t.setCurrencyCode(asCurrencyCode(context.get("currency")));
                        })

                        .wrap(TransactionItem::new));

        // 31.05.2023 31.05.2023 6,63 40,64 Zinsgutschrift Habenzinsen
        Block interestBlock = new Block(
                        "^(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) ([\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) (?<amount>[\\.,\\d-]+) ([\\.,\\d-]+) (Zinsgutschrift) (?<note>.*)");
        type.addBlock(interestBlock);
        interestBlock.set(new Transaction<AccountTransaction>()

                        .subject(() -> {
                            AccountTransaction entry = new AccountTransaction();
                            entry.setType(AccountTransaction.Type.INTEREST);
                            return entry;
                        })

                        .section("date", "amount", "note")
                        .match("^(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) ([\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) (?<amount>[\\.,\\d-]+) ([\\.,\\d-]+) (Zinsgutschrift) (?<note>.*)")
                        .assign((t, v) -> {
                            Map<String, String> context = type.getCurrentContext();

                            t.setDateTime(asDate(v.get("date")));
                            t.setAmount(asAmount(v.get("amount")));
                            t.setCurrencyCode(asCurrencyCode(context.get("currency")));
                            t.setNote(TextUtil.replaceMultipleBlanks(v.get("note")).trim());
                        })

                        .wrap(TransactionItem::new));

        // 19.05.2023 19.05.2023 34,00 34,01 Einzahlung von ...
        Block depositBlock = new Block(
                        "^(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) ([\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) (?<amount>[\\.,\\d-]+) ([\\.,\\d-]+) (Einzahlung) (?<note>.*)");
        type.addBlock(depositBlock);
        depositBlock.set(new Transaction<AccountTransaction>()

                        .subject(() -> {
                            AccountTransaction entry = new AccountTransaction();
                            entry.setType(AccountTransaction.Type.DEPOSIT);
                            return entry;
                        })

                        .section("date", "amount", "note")
                        .match("^(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) ([\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) (?<amount>[\\.,\\d-]+) ([\\.,\\d-]+) (Einzahlung) (?<note>.*)")
                        .assign((t, v) -> {
                            Map<String, String> context = type.getCurrentContext();

                            t.setDateTime(asDate(v.get("date")));
                            t.setAmount(asAmount(v.get("amount")));
                            t.setCurrencyCode(asCurrencyCode(context.get("currency")));
                            t.setNote(TextUtil.replaceMultipleBlanks(v.get("note")).trim());
                        })

                        .wrap(TransactionItem::new));
    }

    private <T extends Transaction<?>> void addTaxesSectionsTransaction(T transaction, DocumentType type)
    {
        transaction
                // Einbehaltene Quellensteuer 15 % auf 2,96 USD 0,37- EUR
                .section("withHoldingTax", "currency").optional()
                .match("^Einbehaltene Quellensteuer [\\d]+ % .* [\\.,\\d]+ [\\w]{3} (?<withHoldingTax>[\\.,\\d]+)\\- (?<currency>[\\w]{3})")
                .assign((t, v) -> processWithHoldingTaxEntries(t, v, "withHoldingTax", type))

                // Anrechenbare Quellensteuer 15 % auf 2,44 EUR 0,37 EUR
                .section("creditableWithHoldingTax", "currency").optional()
                .match("^Anrechenbare Quellensteuer [\\d]+ % .* [\\.,\\d]+ [\\w]{3} (?<creditableWithHoldingTax>[\\.,\\d]+)\\- (?<currency>[\\w]{3})")
                .assign((t, v) -> processWithHoldingTaxEntries(t, v, "creditableWithHoldingTax", type));
    }

    private <T extends Transaction<?>> void addFeesSectionsTransaction(T transaction, DocumentType type)
    {
        transaction
                // Provision 7,90- EUR
                .section("fee", "currency").optional()
                .match("^Provision (?<fee>[.,\\d]+)\\- (?<currency>[\\w]{3})$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Fremde Abwicklungsgebühr für die Umschreibung von Namensaktien 0,60- EUR
                .section("fee", "currency").optional()
                .match("^Fremde Abwicklungsgeb.hr .* (?<fee>[.,\\d]+)\\- (?<currency>[\\w]{3})$")
                .assign((t, v) -> processFeeEntries(t, v, type));
    }
}
