import { assetJourney } from './asset-journey'

// Consumer durable loan: small-ticket, point of sale, auto-sanctioned within the product limit, dealer payout, delivery note.
assetJourney({
  title: 'Consumer durable loan journey (recorded): point-of-sale invoice AI, asset checks, auto-sanction, payout to dealer, delivery note',
  intro: 'Step 1 · At the store: a consumer durable loan for a refrigerator (Consumer durable loan)',
  product: 'CD', name: 'Pooja Pawar', pan: 'POOPP7419P', mobile: '9876500666', dob: '1994-12-12', gender: 'FEMALE',
  city: 'Nashik', pincode: '422003', address: 'Room 14, Panchavati', business: 'Nashik City Hospital', industry: 'SALARIED', vintage: '4',
  income: '38000', expenses: '15000', account: '50100666778899', ifsc: 'HDFC0001234',
  amount: '51960', tenure: '12', purpose: 'Double-door refrigerator',
  amountCaption: '₹51,960 for 12 months: price ₹61,360 less ₹9,400 paid at the counter',
  kyc: [['PAN', 'af-pooja-pan.png'], ['AADHAAR', 'af-pooja-aadhaar.png']],
  dealer: 'Kulkarni Electronics', year: '2026', prefix: 'cd', insurance: false,
  assetCaption: 'Invoice AI: ₹52,000 + GST ₹9,360 = ₹61,360 and the product serial number',
  field: false, memo: false, post: [['DELIVERY_NOTE', 'cd-delivery.pdf']], lienAfter: 'none',
  end: 'Delivery note received within 7 days. End of the consumer durable walkthrough.',
})
