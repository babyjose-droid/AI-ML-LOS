package com.rhythm.los.trade;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TradeInvoiceRepository extends JpaRepository<TradeInvoice, Long> {
    List<TradeInvoice> findByLimitIdOrderByIdDesc(Long limitId);
    List<TradeInvoice> findBySellerGstinIgnoreCaseAndInvoiceNoIgnoreCase(String gstin, String invoiceNo);
    List<TradeInvoice> findByFileHash(String hash);
    List<TradeInvoice> findByCounterpartyId(Long id);
    List<TradeInvoice> findAllByOrderByIdDesc();
}
