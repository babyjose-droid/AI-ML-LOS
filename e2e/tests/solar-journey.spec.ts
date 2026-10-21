import { assetJourney } from './asset-journey'

// Rooftop solar finance: installer payout, installation certificate and net-meter approval after disbursement.
assetJourney({
  title: 'Rooftop solar loan journey (recorded): installer quotation and invoice AI, asset checks, sanction, payout to installer, net meter',
  intro: 'Step 1 · Sales captures a loan for a 5 kW rooftop solar system from an empanelled installer',
  product: 'SOLAR', name: 'Arvind Joshi', pan: 'ARVPJ4730A', mobile: '9876500644', dob: '1978-11-09', gender: 'MALE',
  city: 'Nashik', pincode: '422009', address: 'Bungalow 3, Indira Nagar', business: 'Nashik Polytechnic', industry: 'SALARIED', vintage: '12',
  income: '85000', expenses: '30000', account: '50100644556677', ifsc: 'HDFC0001234',
  amount: '276000', tenure: '36', purpose: '5 kW on-grid rooftop solar for the home',
  amountCaption: '₹2,76,000 for 36 months: system cost ₹3,36,000 less ₹60,000 paid to the installer',
  kyc: [['PAN', 'af-arvind-pan.png'], ['AADHAAR', 'af-arvind-aadhaar.png']],
  dealer: 'SunPeak Solar Installers', year: '2026', prefix: 'solar', insurance: false,
  assetCaption: 'Quotation and invoice AI: ₹3,00,000 + GST ₹36,000 = ₹3,36,000; inverter serial number; insurance not required for solar',
  field: false, memo: true, post: [['INSTALLATION_CERT', 'solar-installation.pdf'], ['NET_METER', 'solar-netmeter.pdf']], lienAfter: 'none',
  end: 'Installation certificate and net-meter approval received within 45 days. End of the rooftop solar walkthrough.',
})
