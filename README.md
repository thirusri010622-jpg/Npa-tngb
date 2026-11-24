# Recovery Field – NPA Management App

Version 1.1 – optimized for large NPA datasets (60,000+ accounts).

## Included
- Offline Room database
- Large-list pagination (100 accounts per page)
- Excel NPA import with flexible column-name detection
- NPA worksheet detection
- Follow-up history stored locally and exported to `Follow up`
- Today / overdue / upcoming follow-up counts
- Outstanding analysis by amount bucket
- Branch filtering and account search
- Local follow-up notifications
- Recovery Field app icon

The project is designed to be built by the GitHub Actions workflow in the repository.


## Member details / workbook format

The app now supports the following workbook sheet names:
- `NPA Data` — main NPA/account data
- `Member details` — group-loan member data linked by `Loan Account No`
- `Follow up` — incremental follow-up/recovery export

On Account Details, `View Members` appears when matching member rows exist. The member list shows Member Name, SB Account No., SB Balance, and the total SB balance.

The Room database uses an indexed member lookup and the account list remains paged, supporting workbooks with at least 60,000 account/member rows without loading the full account list into the account UI.


## Separate Member Details workbook

The main NPA workbook contains `NPA Data` and `Follow up`. Member data is imported from a separate Excel workbook and stored in the local Room database for fast `View Members` lookups. The member importer accepts both `.xls` and `.xlsx` and supports 40,000+ member rows with batched inserts.

Recognized member headers include `Group A/c No` and the expanded Loan Account, Member Name, Member SB Account, and SB Balance variants implemented in `ExcelStore.importMembers`.
