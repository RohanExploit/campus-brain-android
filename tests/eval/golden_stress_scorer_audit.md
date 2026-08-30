# Stresskit scorer audit

120 questions. `contains` = ALL expect strings required; `contains_any` = at least one; `insufficient` = abstention.

| id | route | mode | expect | expected_answer | flag |
|---|---|---|---|---|---|
| F001 | FACT | contains | ['168'] | 168 credits |  |
| F002 | FACT | contains | ['28'] | 28 credits |  |
| F003 | FACT | contains | ['FF', 'XX'] | FF and XX |  |
| F004 | FACT | contains | ['AB'] | No, AB is the second-highest grade and is a strong pass |  |
| F005 | FACT | contains | ['75'] | 75 per cent |  |
| F006 | FACT | contains_any | ['condonation', 'documented'] | Apply for condonation, granted only on documented medical grounds and only once  | MANUAL? |
| F007 | FACT | contains | ['4.50'] | Below 4.50 |  |
| F008 | FACT | contains | ['750'] | Rs. 750 per course |  |
| F009 | FACT | contains_any | ['revaluation', 'results'] | If revaluation results in a change of three or more marks | MANUAL? |
| F010 | FACT | contains | ['8.50'] | 8.50 or above |  |
| F011 | FACT | contains_any | ['improvement', 'Students'] | Students who have availed of any grade improvement attempt | MANUAL? |
| F012 | FACT | contains | ['71', '000'] | Rs. 71,000 |  |
| F013 | FACT | contains | ['4', '25', '000'] | Rs. 4,25,000 |  |
| F014 | FACT | contains | ['200', '6', '000'] | A surcharge of Rs. 200 per day applies, capped at Rs. 6,000 |  |
| F015 | FACT | contains | ['12', '500'] | The one-time admission processing charge of Rs. 12,500 |  |
| F016 | FACT | contains | ['90'] | Within 90 days of programme completion |  |
| F017 | FACT | contains | ['68', '000'] | Rs. 68,000 |  |
| F018 | FACT | contains | ['50'] | 50 per cent |  |
| F019 | FACT | contains | ['20', '000'] | Rs. 20,000 in aggregate per student per academic year |  |
| F020 | FACT | contains | ['1.8'] | Yes, 1.8 per cent |  |
| F021 | FACT | contains_any | ['Dr. Ramchandra Bhosale'] | Dr. Ramchandra Bhosale |  |
| F022 | FACT | contains_any | ['Dr. Meera Joshi'] | Dr. Meera Joshi |  |
| F023 | FACT | contains_any | ['Six'] | Six | MANUAL? |
| F024 | FACT | contains | ['2021'] | 2021 |  |
| F025 | FACT | contains | ['12'] | 12 hours per week |  |
| F026 | FACT | contains_any | ['reviewed', 'papers'] | Two peer-reviewed papers | MANUAL? |
| F027 | FACT | contains_any | ['half'] | As one half | MANUAL? |
| F028 | FACT | contains | ['3', '00', '000'] | Rs. 3,00,000 per project |  |
| F029 | FACT | contains_any | ['continuous', 'service'] | Once every seven years of continuous service | MANUAL? |
| F030 | FACT | contains | ['6.00'] | 6.00 or above |  |
| F031 | FACT | contains_any | ['backlogs', 'active'] | More than two active backlogs | MANUAL? |
| F032 | FACT | contains | ['1.5', 'TPO'] | Only if a later offer is at least 1.5 times the accepted package, with written a |  |
| F033 | FACT | contains | ['44.0', 'LPA'] | Rs. 44.0 LPA |  |
| F034 | FACT | contains | ['79.6'] | 79.6 per cent |  |
| F035 | FACT | contains | ['31'] | Trivendra Systems Private Limited, with 31 offers |  |
| F036 | FACT | contains_any | ['eight', 'weeks'] | Not less than eight weeks | MANUAL? |
| F037 | FACT | contains | ['380'] | 380 beds |  |
| F038 | FACT | contains | ['22', '30'] | 22:30 hours |  |
| F039 | FACT | contains | ['2', '500'] | Rs. 2,500 fine for a first violation and the appliance is confiscated |  |
| F040 | FACT | contains | ['4'] | 4 books |  |
| F041 | FACT | contains | ['5'] | Rs. 5 per book per day |  |
| F042 | FACT | contains | ['300'] | Replace it with an identical edition or pay twice the current market price plus  |  |
| F043 | FACT | contains | ['84', '600'] | 84,600 |  |
| F044 | FACT | contains | ['8.50'] | 8.50 |  |
| F045 | FACT | contains | ['6', '00', '000'] | Rs. 6,00,000 per year |  |
| F046 | FACT | contains | ['25', '000'] | Rs. 25,000 |  |
| F047 | FACT | contains | ['180'] | Rs. 180 per hour |  |
| F048 | FACT | contains | ['20', '10', '50'] | Two in-semester tests of 20 marks each, teacher assessment of 10 marks, end-seme |  |
| F049 | FACT | contains | ['30'] | No, entry is not permitted after 30 minutes from the start |  |
| F050 | FACT | contains | ['III'] | All papers of that examination are cancelled and the candidate is debarred from  |  |
| F051 | FACT | contains_any | ['Rustication', 'years'] | Rustication for two years | MANUAL? |
| F052 | FACT | contains | ['42'] | 42 acres |  |
| F053 | FACT | contains | ['31'] | Approximately 31 per cent |  |
| F054 | FACT | contains_any | ['Sahyadri Infotech'] | Sahyadri Infotech |  |
| F055 | FACT | contains_any | ['comparative', 'statement'] | A three-quotation comparative statement and the approval of the Director | MANUAL? |
| F056 | FACT | contains | ['30'] | Within 30 days of receipt |  |
| F057 | FACT | contains | ['1', '20'] | 1 to 20 |  |
| F058 | FACT | contains_any | ['completion', 'programme'] | Ten years after programme completion | MANUAL? |
| F059 | FACT | contains | ['2', '847'] | 2,847 students |  |
| F060 | FACT | contains | ['10.6'] | 10.6 per cent |  |
| F061 | FACT | contains | ['164', '111'] | 164 papers, of which 111 were Scopus-indexed |  |
| F062 | FACT | contains | ['7.3'] | Rs. 7.3 crore, transferred to the infrastructure development reserve |  |
| F063 | FACT | contains | ['12'] | At least 12 characters |  |
| F064 | FACT | contains | ['AI'] | No. Confidential data must not be transferred to third-party AI services unless  |  |
| F065 | FACT | contains_any | ['discovery', 'Within'] | Within two hours of discovery | MANUAL? |
| F066 | FACT | contains | ['180'] | 180 days |  |
| G001 | GLOBAL | contains | ['94.1', '92.4', '79.4', '60.7', 'CSE'] | CSE is best (94.1% pass, 92.4% placement); Civil is worst (79.4% pass, 60.7% pla |  |
| G002 | GLOBAL | contains | ['CSE'] | Yes, they move together: CSE leads on both, Civil trails on both. The Director a |  |
| G003 | GLOBAL | contains | ['AI'] | Faculty vacancy in AI & Data Science with one head holding charge of two departm |  |
| G004 | GLOBAL | contains | ['31', '19', 'HPC', 'AMC'] | Trivendra Systems (HPC supplier and largest recruiter, 31 offers) and Sahyadri I |  |
| G005 | GLOBAL | contains | ['18', '26', '41', '2', '96', '35', '000'] | Konkan 18.40L + Sahyadri 26.75L + Godavari 210L + Pratap 41.20L = Rs. 2,96,35,00 |  |
| G006 | GLOBAL | contains | ['142000', '12500', '4800', '3200', '68000', '41000', '2', '71', '500', '15000', '1', '62'] | Hosteller: 142000+12500+4800+3200+68000+41000 = Rs. 2,71,500 plus refundable 150 |  |
| G007 | GLOBAL | contains | ['2023', '10', '2', 'DPDP', 'AI'] | DPDP Act 2023 compliance, 10-year retention then anonymised archive, Registrar a |  |
| G008 | GLOBAL | contains_any | ['Dr. Sunanda Kulkarni', 'Dr. Iqbal Shaikh', 'Registrar Shri Prakash Deshmukh'] | Dean (Academics) Dr. Sunanda Kulkarni, Dean (R&D) Dr. Iqbal Shaikh, and the Regi |  |
| G009 | GLOBAL | contains | ['6', '000', '5', '1', '2', '500', '750', '200'] | Late fee surcharge up to Rs. 6,000; library overdue Rs. 5/day; hostel late-retur |  |
| G010 | GLOBAL | contains | ['CSE', 'AI', 'IT'] | Dr. Meera Joshi heads CSE and holds additional charge of AI & Data Science, and  |  |
| G011 | GLOBAL | contains | ['71.4', '64.1', '7.3', '58.9'] | Income Rs. 71.4 cr against expenditure Rs. 64.1 cr, surplus Rs. 7.3 cr. Fees are |  |
| G012 | GLOBAL | contains | ['21'] | Ragging: hostel and Institute expulsion, one semester to four semesters rusticat |  |
| G013 | GLOBAL | contains_any | ['Grievance Redressal', 'Dean Academics Dr', 'Sunanda Kulkarni', 'Internal Complaints', 'Rukmini Sathe', 'Malpractice Enquiry', 'Academic Review Committee'] | Grievance Redressal (Dean Academics Dr. Sunanda Kulkarni), Anti-Ragging (Directo |  |
| G014 | GLOBAL | contains | ['4.62', '17', '51.30', '22', '5.13'] | External Rs. 4.62 crore across 17 projects plus internal seed grants Rs. 51.30 l |  |
| G015 | GLOBAL | contains | ['1996', '2021', 'AI'] | Mechanical, Civil and Applied Sciences established 1996 (oldest); AI & Data Scie |  |
| G016 | GLOBAL | contains | ['12', '16', '1', '2', '20'] | Teaching 12-16 hrs/week by cadre, 1-2 peer-reviewed papers by cadre, monthly men |  |
| G017 | GLOBAL | contains | ['5', '00', '000', '25', '1.94', 'HPC'] | Above Rs. 5,00,000 needs three quotations and Director approval; above Rs. 25,00 |  |
| G018 | GLOBAL | contains | ['1', '140', '620', '380', '2', '847', '40', 'PG'] | 1,140 beds (620 male, 380 female, 140 PG) against enrolment of 2,847, so roughly |  |
| G019 | GLOBAL | contains_any | ['Institute Merit Scholarship', 'Earn While You Learn'] | Institute Merit Scholarship is not tenable with any State or Central scholarship |  |
| G020 | GLOBAL | contains | ['20', '05', '01', '15', '10'] | Fee instalments 20 July and 05 December; scholarship applications 01 Aug to 15 S |  |
| G021 | GLOBAL | contains | ['480', '2024', '31', '210'] | 480 kWp rooftop solar commissioned Nov 2024 meeting ~31% of daytime load with si |  |
| G022 | GLOBAL | contains | ['132', '118', '14', 'AI'] | Sanctioned 132 against 118 actual, a gap of 14 concentrated in AI & Data Science |  |
| L001 | LOCAL | contains_any | ['Dr. Vasant Rane', 'Chief Warden', 'Mechanical Engineering'] | Dr. Vasant Rane, Chief Warden, heads Mechanical Engineering. |  |
| L002 | LOCAL | contains_any | ['Trivendra Systems', 'High Performance Computing Laboratory'] | Trivendra Systems supplied the High Performance Computing Laboratory. |  |
| L003 | LOCAL | contains_any | ['Dr. Iqbal Shaikh'] | Dr. Iqbal Shaikh, Dean (Research and Development). |  |
| L004 | LOCAL | contains_any | ['Dr. Vasant Rane', 'Advanced Manufacturing Laboratory'] | Dr. Vasant Rane is in-charge of the Advanced Manufacturing Laboratory. |  |
| L005 | LOCAL | contains_any | ['Shri Balasaheb Jadhav', 'Registrar Shri Prakash Deshmukh'] | Shri Balasaheb Jadhav reports to Registrar Shri Prakash Deshmukh, who also is Co |  |
| L006 | LOCAL | contains_any | ['Konkan Facility Services', 'Dr. Vasant Rane', 'Chief Warden'] | Konkan Facility Services maintains hostel plumbing and electrical work; Dr. Vasa |  |
| L007 | LOCAL | contains | ['ICC'] | Smt. Rukmini Sathe chairs the ICC and is Deputy Warden (Girls). |  |
| L008 | LOCAL | contains_any | ['Dr. Farida Merchant', 'Applied Sciences'] | Dr. Farida Merchant coordinates scholarships and heads Applied Sciences and Huma |  |
| L009 | LOCAL | contains_any | ['Dr. Farida Merchant', 'Applied Sciences'] | Dr. Farida Merchant is grievance nodal officer and scholarship coordinator, and  |  |
| L010 | LOCAL | contains | ['AI'] | Dr. Meera Joshi heads two: Computer Science and Engineering, and AI & Data Scien |  |
| L011 | LOCAL | contains_any | ['Shri Nitin Kharche', 'Dr. Sunanda Kulkarni', 'Grievance Redressal Committee'] | Shri Nitin Kharche reports to Dean (Academics) Dr. Sunanda Kulkarni, who chairs  |  |
| L012 | LOCAL | contains_any | ['Dr. Shalini Gokhale', 'Civil Engineering'] | Dr. Shalini Gokhale leads Civil Engineering. |  |
| L013 | LOCAL | contains | ['31', '2026'] | Godavari Catering, expiring 31 May 2026; Dr. Vasant Rane is Chief Warden. |  |
| L014 | LOCAL | contains_any | ['The Director', 'Dr. Ramchandra Bhosale', 'Dr. Sunanda Kulkarni'] | The Director, Dr. Ramchandra Bhosale, on the recommendation of Dean (Academics)  |  |
| L015 | LOCAL | contains_any | ['Dr. Anil Pawar', 'Telecommunication Engineering'] | Dr. Anil Pawar leads Electronics and Telecommunication Engineering; the granted  |  |
| L016 | LOCAL | contains_any | ['The Director Dr', 'Ramchandra Bhosale', 'Ragging Committee'] | The Director Dr. Ramchandra Bhosale, who chairs the Anti-Ragging Committee. |  |
| L017 | LOCAL | contains | ['19'] | Sahyadri Infotech, 19 offers. |  |
| L018 | LOCAL | contains_any | ['Shri Prakash Deshmukh'] | Shri Prakash Deshmukh issued the fee structure (as Registrar) and the examinatio |  |
| L019 | LOCAL | contains_any | ['Dr. Sunanda Kulkarni', 'Director Dr', 'Ramchandra Bhosale'] | Dr. Sunanda Kulkarni, Dean (Academics), reports to the Director Dr. Ramchandra B |  |
| L020 | LOCAL | contains | ['60.7'] | Civil Engineering at 60.7 per cent, headed by Dr. Shalini Gokhale, owning the St |  |
| U001 | UNANS | insufficient | [] | Not stated in the corpus |  |
| U002 | UNANS | insufficient | [] | No such department exists at the Institute |  |
| U003 | UNANS | insufficient | [] | Not stated; only 2024-25 and 2023-24 figures appear |  |
| U004 | UNANS | insufficient | [] | The Institute has a Director, not a Vice Chancellor |  |
| U005 | UNANS | insufficient | [] | No individual student records exist in this corpus |  |
| U006 | UNANS | insufficient | [] | Only two-seater and three-seater rates are stated |  |
| U007 | UNANS | insufficient | [] | Only the current contract value is stated |  |
| U008 | UNANS | insufficient | [] | Not stated in the corpus |  |
| U009 | UNANS | insufficient | [] | No affiliation to any IIT is stated |  |
| U010 | UNANS | insufficient | [] | Only CSE (highest) and Civil (lowest) departmental pass percentages are stated |  |
| U011 | UNANS | insufficient | [] | Only 2024-25 figures are given: three filed, one granted |  |
| U012 | UNANS | insufficient | [] | Not stated in the corpus |  |