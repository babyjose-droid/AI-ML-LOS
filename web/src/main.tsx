import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import VideoKycCustomer from './vkyc/VideoKycCustomer'
import BorrowerPortal from './borrower/BorrowerPortal'
import './styles.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      {window.location.pathname.startsWith('/vkyc/') ? <VideoKycCustomer /> : window.location.pathname.startsWith('/apply') ? <BorrowerPortal /> : <App />}
    </BrowserRouter>
  </React.StrictMode>,
)
