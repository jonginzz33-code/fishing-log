# 서버 규칙 시험 (Firestore 에뮬레이터)
npm i firebase-tools@13.29.1 @firebase/rules-unit-testing@3.0.4 firebase@10.14.1
npx firebase emulators:exec --only firestore --project demo-fl "node test.mjs ../firestore.rules"
