# Audit user journey OCTO dashboard — end to end

Tanggal: 6 Okt 2026 · Cakupan: `/app` (dashboard), frontend saja · Basis: branch `audit/web-dashboard-production`

## 1. Metode

- **Janji produk** diambil dari situs resmi (octo.mesta.click): IBOR yang posisinya dihitung dari ledger, AI yang bisa diaudit ("every answer shows its work", keputusan besar butuh sign-off manusia), setiap keputusan tercatat atas nama orang yang login, dan "from first look to LP report" dengan angka yang sama.
- **Kontrak backend** dibaca langsung dari `modules/api` (Kotlin) dan `agents/octo_agents` (sidecar AI). Alur di UI hanya menawarkan apa yang benar-benar diterima server.
- **Setiap halaman dan tombol diklik** di browser terhadap mock API lokal yang meniru aturan server: state machine prospect, pemisahan tugas approval, task review, dan release gate. Ada 32 skenario API yang lolos semua, ditambah walkthrough UI per halaman. Mock itu alat verifikasi lokal dan **tidak termasuk dalam PR ini**.
- **Checklist UX** dari brief (kejelasan, hierarki, state, error, aksesibilitas, konsistensi, kepercayaan data, onboarding) dipakai sebagai lensa penilaian.

## 2. Siapa memakai apa

| Persona | Pertanyaan yang dibawa | Halaman utama |
|---|---|---|
| Deal team (associate, VP) | "Deal mana yang perlu saya gerakkan hari ini?" | Deals, Company brain |
| IC approver | "Apa yang menunggu keputusan saya, dan apakah bukti cukup?" | Deals (IC review), Agent runs |
| Portfolio ops / fund accounting | "Apakah buku kita cocok dengan custodian / admin?" | Reconciliation |
| Compliance officer | "Apakah fund melanggar limit per tanggal X?" | Compliance, Agent runs |
| Investor relations | "Angka LP report mana yang sudah boleh keluar?" | Reports |
| Semua role | "Apa yang dikerjakan AI atas nama kami?" | Agent runs |

## 3. Peta journey end to end

```
Source ──► Screen ──► Diligence ──► IC decision ──► Invested
 (Deals)    (rules +    (evidence     (AI memo →      (audit trail +
            AI first     checklist +   approver ≠      posisi masuk IBOR
            look)        gap tasks)    requester)      saat transaksi dibukukan)
                                                            │
  Reconciliation ◄── posisi & kas dari ledger ◄────────────┘
  (source vs IBOR → break → review task → koreksi ledger)
        │
  Compliance (figures vs limit per tanggal → breach → review task → AI rationale)
        │
  Reports (cash flows + NAV → hitung → sealed → approval → release + SHA-256)

Lintas semua langkah: Agent runs (setiap draf AI, verdict judge, review manusia)
                      Company brain (tanya-jawab read-only atas pipeline & riwayat)
```

**Sebelum audit**, setiap halaman live hanya berupa form mentah tanpa konteks. Tidak ada tautan antar langkah: hasil AI tidak bisa ditelusuri ke deal-nya, dan deal tidak menunjukkan draf AI-nya. **Setelah audit**, setiap halaman punya panduan "How this works", setiap aksi menjelaskan apa yang terjadi berikutnya, dan setiap hasil AI tertaut dua arah ke subjeknya.

## 4. Temuan per halaman (sebelum → sesudah)

Severity: **K** = kritis (alur salah/menyesatkan), **T** = tinggi (user bingung/terhenti), **S** = sedang.

### 4.1 Company detail — tombol "Request mark" & "Board pack"
- **K · Board pack menyesatkan.** Tombol ini membuka form *performance report level fund*, padahal API tidak punya tipe report "board pack". → Sekarang **Latest board pack** membuka tab Documents yang memang memuat board pack September.
- **T · Request mark tanpa penjelasan.** Sebelumnya hanya muncul toast. → Sekarang dialog konfirmasi menjelaskan: tanggal mark terakhir, umurnya terhadap kebijakan 30 hari, bahwa NAV tetap memakai mark lama sampai yang baru disetujui, dan bahwa permintaan masuk antrian tim valuasi (demo, tidak terkirim). Tombol ini otomatis jadi tombol utama saat mark sudah melewati 30 hari.

### 4.2 Deals — "kanban ini gunanya apa, cuma geser-geser?"
Bukan sekadar geser. Setiap perpindahan adalah **event ter-audit** dengan nama aktor, dan sebagian wajib punya alasan. Karena itu perpindahan memakai tombol, bukan drag. Masalahnya, versi lama tidak menunjukkan ini:
- **K · Admin bisa klik Approve IC, padahal server menolak.** API hanya mengizinkan role `approver` untuk memutuskan (admin sengaja dipisahkan dari tugas approval). → `canDecide = approver`.
- **K · 8 endpoint tidak dipakai**: detail prospect, riwayat event, rule screening, AI first look, AI IC memo, flag evidence diligence, daftar invested, daftar passed. Alur "first look → IC" yang dijanjikan situs tidak bisa dijalankan dari UI. → Sekarang semua tersedia di **deal sheet**.
- **T · Kartu tidak menunjukkan langkah berikutnya.** → Setiap kartu kini menampilkan "Next: …" atau badge "Awaiting an approver". Setiap kolom menjelaskan arti stage-nya.
- **T · Register hanya dengan nama.** Rule screening dan AI bekerja dari sector, region, dan thesis. → Form **Add prospect** mengisi semuanya, dengan penjelasan kenapa field itu penting.
- **T · Pemisahan tugas tidak dijelaskan.** → Deal sheet menyebut siapa yang me-request. Kalau user mencoba meng-approve request-nya sendiri, server menolak dan UI menjelaskan: "You requested this review, so another approver must decide it."
- **S · Deal yang sudah selesai hilang.** → Tab **Invested / Passed** menampilkan siapa yang memutuskan dan alasannya.

Alur di deal sheet per stage:

| Stage | Aksi yang ditawarkan | Hasil yang dijelaskan |
|---|---|---|
| Sourced | Move to screening | — |
| Screening | Run screening rules · AI first look · Advance | Clear / Needs a person (task review) / Screened out (otomatis passed + alasan) |
| Due diligence | AI diligence review · Flag missing evidence (per workstream) · Advance | Dossier, risiko per workstream, kelengkapan evidence; task evidence dibuka atau sudah ada (dedupe) |
| IC review | Draft IC memo & request approval · Request tanpa memo · Approve / Reject (approver lain) · Record as invested | Status review, siapa yang me-request atau memutuskan, alasan reject |
| Semua | Pass (wajib alasan) | Tercatat di audit trail |

### 4.3 Reconciliation
- **T · User tidak tahu sumber apa yang dicek, kenapa, dan apa arti hasilnya.** → Halaman kini dibagi tiga langkah: (1) pilih sumber, (2) masukkan record, (3) atur ketelitian.
- **T · Input manual satu per satu.** → Ada **Paste from a spreadsheet** (CSV/TSV, header otomatis dilewati) dan validasi per baris, termasuk referensi duplikat.
- **T · Opsi API tidak dipakai**: toleransi jumlah/hari dan *complete record set* (satu-satunya cara mendeteksi "missing in source"). → Keduanya ada, dengan penjelasan.
- **T · Hasil berupa daftar mentah.** → Hasil menampilkan ringkasan (submitted / matched / breaks) dan break dikelompokkan per jenis, masing-masing dengan arti, cara perbaikan yang lazim, dan task-nya. Ditegaskan juga bahwa perbaikan dilakukan lewat koreksi ledger, bukan edit (sesuai janji "nothing overwritten").

### 4.4 Compliance
- **K · Input salah bentuk.** UI lama hanya meminta exposure per currency, padahal rule `concentration-limit` butuh exposure per aset dan `coverage-floor` butuh rasio coverage. Rule-rule itu selalu jadi "not evaluable" tanpa user tahu kenapa. → Form kini **hanya menampilkan input yang dibutuhkan rule aktif**, dan sebelum dijalankan memberi tahu berapa rule yang akan jadi not evaluable.
- **T · Rule tampil sebagai kode** (`currency-idr-cap · v3`). → Setiap rule dibaca sebagai kalimat ("IDR may be at most 40% of currency exposure"), dengan tag jenisnya.
- **T · Rule tidak bisa dikelola.** API punya endpoint define dan retire untuk approver/admin. → Ada **Add rule** (dengan preview kalimat) dan **Retire** (dengan konfirmasi yang menjelaskan bahwa riwayat tetap tersimpan).
- **T · Hasil sulit dibaca.** → Setiap outcome punya badge, penjelasan, **gauge nilai terukur vs limit**, arti hasilnya, dan task review untuk breach. Halaman otomatis scroll ke hasil. AI rationale tertaut ke Agent runs.

### 4.5 Agent runs — kesinambungan dengan halaman lain
- **K · Putus dari halaman lain.** Daftar lama hanya `workflow · subject/uuid`. → Setiap run kini berlabel bahasa manusia ("IC memo", "Compliance rationale") dengan subjek bernama ("Deal: Kirana Consumer · IC review") dan tombol **Open the deal / Open compliance / Open company brain**. Sebaliknya, deal sheet, Compliance, dan Brain juga menaut balik ke run-nya.
- **T · Tidak ada detail.** API `GET /agent-runs/{id}` tidak dipakai. → Sheet detail memuat: arti status, apa yang ditanyakan, apa yang dihasilkan, verdict judge (bar probabilitas), model, durasi, dan error.
- **T · "People close the loop" belum ada.** API `POST /outcome` tidak dipakai. → Ada **I agree / I disagree — override** (override wajib catatan), tersimpan atas nama user, dipakai untuk kalibrasi judge.
- **S · Tidak ada prioritas.** → Ringkasan: Need attention, Running, Completed, Awaiting a human review. Ada juga filter status, workflow, dan pencarian, plus deep link `?run=` / `?workflow=`.

### 4.6 Reports
- **K · Tanda arus kas membingungkan.** User harus mengetik angka negatif untuk kontribusi. → Pilih **Contribution / Distribution** dan masukkan angka positif. Tanda investor diterapkan otomatis.
- **T · Gerbang release tidak terlihat.** → Setiap job punya stepper **Queued → Computed → Release requested → Approved & released**, dengan penjelasan bahwa hasil tetap tersegel sampai approver lain menyetujui. Status release kini dibaca dari `GET /release` (sebelumnya tidak dipakai), sehingga angka dan SHA-256 muncul begitu dirilis.
- **T · Validasi diam.** Tombol mati tanpa alasan. → Pesan per field: currency, NAV, tanggal, arus kas setelah tanggal valuasi, measure.
- **S · Schedule tidak terlihat.** → Panel **Scheduled reports** (aktif/paused, measure, jadwal berikutnya).
- **S · Measure tanpa arti.** → Setiap measure (TVPI, DPI, RVPI, IRR) punya keterangan singkat.

### 4.7 Company brain
- **K · Cakupan tidak jujur.** Sidecar brain hanya punya tool pipeline, prospect + riwayat, dan master aset. UI lama tidak menyebutnya, sehingga user bertanya soal NAV dan selalu ditolak tanpa tahu kenapa. → Panel **What it can answer today / Not yet**, contoh pertanyaan yang memang bisa dijawab, dan saran pertanyaan lain saat ditolak.
- **T · Satu jawaban, lalu hilang.** → Riwayat tanya-jawab sesi ini, skor judge ("answerable 95% · supported 92%"), dan tautan ke run di Agent runs.
- **T · Keberlanjutan tidak jelas.** → Ditegaskan bahwa brain hanya membaca dan tidak pernah mengubah apa pun. Untuk bertindak, user diarahkan ke pipeline.

## 5. Temuan lintas halaman

| # | Temuan | Status |
|---|---|---|
| X1 | Error generik ("request failed (HTTP 409)") tidak menjelaskan arti dan langkah berikutnya | **Selesai**: pesan per aksi per status (409 SoD, 404 izin role, 503 AI tidak tersedia, dst.) |
| X2 | User tidak tahu kenapa aksi tidak tersedia | **Selesai**: viewer melihat banner "restricted" yang menjelaskan role yang dibutuhkan |
| X3 | Empty state hanya "Nothing here yet" | **Selesai**: menjelaskan kenapa kosong dan apa yang mengisinya |
| X4 | Data demo dan data live bercampur tanpa batas jelas | **Selesai** (iterasi sebelumnya): setiap halaman sepenuhnya live atau demo, dengan badge |
| X5 | Checkbox design-system tanpa label terlihat | **Selesai**: `CheckRow` |
| X6 | Input dengan nama aksesibilitas ganda ("Exposure 1" ×2) | **Selesai** |
| X7 | Panduan halaman memenuhi layar untuk user berpengalaman | **Selesai**: bisa dilipat, pilihan diingat per halaman |

## 6. Celah yang butuh backend (tidak bisa diselesaikan di frontend)

1. **Tidak ada inbox task.** Recon break, compliance breach, IC review, dan evidence request semuanya membuka *workflow task*, tapi tidak ada `GET /tasks`. Halaman **Workflows** masih demo, jadi task live hanya terlihat sebagai ID. Ini celah terbesar: tidak ada tempat untuk "menyelesaikan" task.
2. **Tidak ada daftar recon run maupun report job.** Hasil hanya terlihat di sesi yang menjalankannya. Job report diingat per tab browser.
3. **Screening rule tidak bisa dibaca.** Hanya ada define dan retire, jadi UI tidak bisa menampilkan mandat yang dipakai screening.
4. ~~**Approval release report**~~ — **sudah tertutup**: `main` menambahkan `POST /reports/{id}/release/decision`, dan halaman Reports kini memakainya. Approver melihat draft tersegel, lalu Approve atau Reject (wajib alasan). Requester tidak bisa memutuskan release-nya sendiri.
5. **Identitas user lokal.** Tanpa Supabase, UI tidak tahu siapa "saya", sehingga tombol Approve tetap muncul untuk request sendiri dan baru ditolak server (dengan pesan yang jelas).
6. **Halaman demo** (Portfolio, Funds, Investments, Companies, Workflows, Alerts, Analytics, Data, Settings) belum punya API. Aksi di sana hanya toast "(demo)".

## 7. Bukti verifikasi

- `npx tsc --noEmit` bersih. `npm test` 15/15. `next build` sukses.
- 32 skenario alur API terhadap mock lolos semua. Contohnya: register → screen clear → AI first look → DD (checklist dibuka) → flag evidence (dedupe) → IC review → AI memo membuka approval → requester ditolak meng-approve (SoD) → approver lain approve → invested. Ditambah compliance 3 jenis rule, recon 4 jenis break, report sealed → released, dan brain jawab/tolak.
- Walkthrough UI di browser (1440px): Deals board + deal sheet + pesan SoD, Deals → Agent runs → kembali ke deal, Compliance (input → hasil + gauge + rationale), Reconciliation (paste → 3 break dengan arti), Reports (validasi → queue → release → angka + SHA), Brain (jawab dan tolak).

## 8. File

- Halaman live baru: `web/components/live/` — `deals-board.tsx`, `prospect-sheet.tsx`, `agent-runs.tsx`, `compliance.tsx`, `reconciliation.tsx`, `reports.tsx`, `brain.tsx`, `common.tsx`, `deals-api.ts`, `agent-runs-api.ts`
- Rute: `web/app/app/{deals,reconciliation,compliance,agents,reports,brain}/page.tsx`
- Company detail: `web/components/views/company-detail.tsx`
- AI analysis: `web/app/app/analysis/page.tsx` memasang `analysis-panel.tsx` dari `main` (equity bridge, DDQ, operating review), supaya fitur itu tidak hilang saat shell lama diganti
- Dihapus karena digantikan halaman di atas: `pipeline-board.tsx`, `recon-panel.tsx`, `compliance-panel.tsx`, `agent-runs-panel.tsx`, `report-queue.tsx`, `brain-panel.tsx`, `blocks/app-shell-2.tsx`, `blocks/command-menu-1.tsx`, `session-menu.tsx`
